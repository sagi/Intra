package app.intra.socks;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import app.intra.util.Names;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.analytics.FirebaseAnalytics.Param;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import sockslib.server.Session;
import sockslib.server.io.Pipe;
import sockslib.server.io.PipeListener;
import sockslib.server.io.StreamPipe;
import sockslib.server.msg.CommandMessage;
import sockslib.server.msg.CommandResponseMessage;
import sockslib.server.msg.ServerReply;


/**
 * This SocksHandler acts as a normal Socks5Handler, except that TCP sockets can be retried after
 * some failure types.  This class inherits from UdpOverrideSocksHandler so that SocksServer can
 * have both behaviors.  This class is separate from UdpOverrideSocksHandler to make the code more
 * comprehensible.
 */
 public class OverrideSocksHandler extends UdpOverrideSocksHandler {
  // Names for the upload and download pipes for monitoring.
  private static final String UPLOAD = "upload";
  private static final String DOWNLOAD = "download";

  // App context, used to emit Firebase Analytics events
  private Context context = null;

  // On retries, limit the first packet to a random length 32-64 bytes (inclusive).
  private static final int MIN_SPLIT = 32, MAX_SPLIT = 64;
  private static final Random RANDOM = new Random();

  // Protocol and error identification constants.
  private static final int HTTPS_PORT = 443;
  private static final int HTTP_PORT = 80;
  private static final String RESET_MESSAGE = "Connection reset";

  // Simulate reset events.  Set to true only for testing.
  private static final boolean SIMULATE_RESET = true;

  void setContext(Context context) {
    this.context = context;
  }

  @Override
  public void doConnect(Session session, CommandMessage commandMessage) throws IOException {
    ServerReply reply = null;
    Socket socket = null;
    InetAddress bindAddress = null;
    int bindPort = 0;
    InetAddress remoteServerAddress = commandMessage.getInetAddress();
    int remoteServerPort = commandMessage.getPort();

    // set default bind address.
    byte[] defaultAddress = {0, 0, 0, 0};
    bindAddress = InetAddress.getByAddress(defaultAddress);
    // DO connect
    try {
      // Connect directly.
      socket = new Socket(remoteServerAddress, remoteServerPort);
      bindAddress = socket.getLocalAddress();
      bindPort = socket.getLocalPort();
      reply = ServerReply.SUCCEEDED;

    } catch (IOException e) {
      if (e.getMessage().equals("Connection refused")) {
        reply = ServerReply.CONNECTION_REFUSED;
      } else if (e.getMessage().equals("Operation timed out")) {
        reply = ServerReply.TTL_EXPIRED;
      } else if (e.getMessage().equals("Network is unreachable")) {
        reply = ServerReply.NETWORK_UNREACHABLE;
      } else if (e.getMessage().equals("Connection timed out")) {
        reply = ServerReply.TTL_EXPIRED;
      } else {
        reply = ServerReply.GENERAL_SOCKS_SERVER_FAILURE;
      }
      logger.info("SESSION[{}] connect {} [{}] exception:{}", session.getId(), new
          InetSocketAddress(remoteServerAddress, remoteServerPort), reply, e.getMessage());
    }

    CommandResponseMessage responseMessage =
        new CommandResponseMessage(VERSION, reply, bindAddress, bindPort);
    session.write(responseMessage);
    if (reply != ServerReply.SUCCEEDED) {
      session.close();
      return;
    }

    // Code prior to this point is essentially unmodified from sockslib.  At this point, sockslib
    // normally creates a SocketPipe to pass the data.  We change the behavior here to allow retry.

    final OutputStream sessionDownload = session.getOutputStream();
    final InputStream sessionUpload = session.getInputStream();
    InputStream socketDownload = socket.getInputStream();
    OutputStream socketUpload = socket.getOutputStream();

    final StreamPipe upload = new StreamPipe(sessionUpload, socketUpload, UPLOAD);
    upload.setBufferSize(BUFFER_SIZE);
    StreamPipe download = new StreamPipe(socketDownload, sessionDownload, DOWNLOAD);
    download.setBufferSize(BUFFER_SIZE);

    StatsListener listener = null;
    try {
      listener = runPipes(upload, download, remoteServerPort, SIMULATE_RESET && remoteServerPort == HTTPS_PORT);
      if (remoteServerPort == HTTPS_PORT && listener.downloadBytes == 0 && listener.uploadBytes > 0
          && listener.error != null && RESET_MESSAGE.equals(listener.error.getMessage())
          && listener.uploadBuffer != null) {
        socket.close();
        download.stop();
        socket = new Socket(remoteServerAddress, remoteServerPort);
        socket.setTcpNoDelay(true);
        socketDownload = socket.getInputStream();
        socketUpload = socket.getOutputStream();

        download = new StreamPipe(socketDownload, sessionDownload, DOWNLOAD);
        download.setBufferSize(BUFFER_SIZE);

        // upload is blocked in a read from sessionUpload, which cannot be canceled.
        // However, we have modified StreamPipe to have a setter for the destination stream, so
        // that the next transfer will be uploaded into the new socket instead of the old one.
        upload.setDestination(socketUpload);

        listener = splitRetry(upload, download, socketUpload, listener);
      }
    } catch (InterruptedException e) {
      // An interrupt is a normal lifecycle event and indicates that we should perform a clean
      // shutdown.
    }

    // Terminate and release both sockets.
    upload.stop();
    download.stop();
    socket.close();
    session.close();

    if (listener != null) {
      // We had a functional socket long enough to record statistics.
      // Report the BYTES event : value = total transfer over the lifetime of a socket
      // - PORT : TCP port number (i.e. protocol type)
      // - DURATION: socket lifetime in seconds

      Bundle event = new Bundle();
      event.putInt(Param.VALUE, listener.uploadBytes + listener.downloadBytes);

      int port = listener.port;
      if (port >= 0) {
        if (port != HTTP_PORT && port != HTTPS_PORT && port != 0) {
          // Group all other ports together into an "other" bucket.
          port = -1;
        }
        event.putInt(Names.PORT.name(), port);
      }

      int durationMs = listener.getDurationMs();
      if (durationMs >= 0) {
        // Socket connection duration is in seconds.
        event.putInt(Names.DURATION.name(), durationMs / 1000);
      }

      FirebaseAnalytics.getInstance(context).logEvent(Names.BYTES.name(), event);
    }
  }

  /**
   * This class collects metadata about a proxy socket, including its duration, the amount
   * transferred, and whether it was terminated normally or by an error.
   *
   * It also replicates the wasStopped() and await() functions from Socks5Handler.StopListener.
   */
  private static class StatsListener implements PipeListener {
    // System clock start and stop time for this socket pair.
    long startTime = -1;
    long stopTime = -1;

    // If non-null, the socket was closed with an error.
    Exception error = null;

    // Counters for upload and download total size and number of transfers.
    int uploadBytes = 0;
    int uploadCount = 0;
    int downloadBytes = 0;

    // Server port, for protocol identification.
    int port;

    boolean simulateReset;

    // The upload buffer is only intended to hold the first flight from the client.
    private static final int MAX_BUFFER = 1024;
    private ByteBuffer uploadBuffer = null;

    // Incorporate the functionality of StopListener in Socks5Handler.
    private final CountDownLatch latch = new CountDownLatch(1);
    private boolean stopped = false;

    /**
     * Blocks until onStop is called.
     * @throws InterruptedException if this thread is interrupted.
     */
    void await() throws InterruptedException {
      latch.await();
    }

    boolean wasStopped() {
      return stopped;
    }

    StatsListener(int port, boolean simulateReset) {
      this.port = port;
      this.simulateReset = simulateReset;
      if (port == HTTPS_PORT) {
        uploadBuffer = ByteBuffer.allocateDirect(MAX_BUFFER);
      }
    }

    int getDurationMs() {
      if (startTime < 0 || stopTime < 0) {
        return -1;
      }
      return (int)(stopTime - startTime);
    }

    @Override
    public void onStart(Pipe pipe) {
      startTime = SystemClock.elapsedRealtime();
    }

    @Override
    public void onStop(Pipe pipe) {
      stopTime = SystemClock.elapsedRealtime();
      stopped = true;
      latch.countDown();
    }

    @Override
    public void onTransfer(Pipe pipe, byte[] buffer, int bufferLength) {
      if (stopped) {
        return;
      }
      if (DOWNLOAD.equals(pipe.getName())) {
        downloadBytes += bufferLength;
        // Free memory in uploadBuffer, since it's no longer needed.
        uploadBuffer = null;
      } else {
        uploadBytes += bufferLength;
        if (uploadBuffer != null) {
          try {
            uploadBuffer.put(buffer, 0, bufferLength);
          } catch (Exception e) {
            uploadBuffer = null;
          }
        }
        ++uploadCount;
        if (simulateReset) {
          pipe.removePipeListener(this);
          onError(pipe, new Exception(RESET_MESSAGE));
          onStop(pipe);
        }
      }
    }

    @Override
    public void onError(Pipe pipe, Exception exception) {
      this.error = exception;
    }
  }

  /**
   * This function starts pipes for upload and download and blocks until one of them stops.
   * @param upload A pipe that has not yet been started
   * @param download A pipe that has not yet been started
   * @param port The remote server port number
   * @throws IOException if there is a network error
   * @throws InterruptedException if this thread is interrupted.
   * @return A StatsListener containing final stats on the socket, which has now been closed.
   */
  private static StatsListener runPipes(final Pipe upload, final Pipe download, int port, boolean simulateReset) throws InterruptedException {

    StatsListener listener = new StatsListener(port, simulateReset);
    upload.addPipeListener(listener);
    download.addPipeListener(listener);

    // TODO: Propagate half-closed socket state correctly.  (Sockslib does not do this.)
    upload.start();
    download.start();

    // In normal operation, pipe.isRunning() and !listener.wasStopped() should be
    // equal.  However, they can be different if start() failed, or if there is a
    // bug in SocketPipe or StreamPipe.  Checking both ensures that there is no
    // possibility of a hang or busy-loop here.
    while (upload.isRunning() && download.isRunning() && !listener.wasStopped()) {
      listener.await();
    }
    return listener;
  }

  /**
   * Retry a connection, splitting the initial segment.  Blocks until the new attempt succeeds or
   * fails.
   * @param upload A started pipe on which some data has already been transferred
   * @param download A new pipe on which no data has been transfered
   * @param socketUpload The OutputStream half of upload.
   * @param listener The listener from the previous (failed) proxy attempt.
   * @return A new StatsListener if the connection succeeded, or the old listener if it failed.
   * @throws InterruptedException if this thread is interrupted.
   */
  private StatsListener splitRetry(final Pipe upload, final Pipe download, final OutputStream socketUpload,
      StatsListener listener) throws InterruptedException {
    // Prepare an EARLY_RESET event to collect metrics on success rates for splitting:
    // - BYTES : Amount uploaded before reset
    // - CHUNKS : Number of upload writes before reset
    // - RETRY : 1 if retry succeeded, otherwise 0
    Bundle event = new Bundle();
    event.putInt(Names.BYTES.name(), listener.uploadBytes);
    event.putInt(Names.CHUNKS.name(), listener.uploadCount);

    int limit = MIN_SPLIT + (Math.abs(RANDOM.nextInt()) % (MAX_SPLIT + 1 - MIN_SPLIT));
    event.putInt(Names.SPLIT.name(), limit);

    try {

      // Send the first 32-64 bytes in the first packet.
      final int position = listener.uploadBuffer.position();
      final int offset = listener.uploadBuffer.arrayOffset();
      final int length = position - offset;
      final int split = Math.min(limit, length / 2);
      socketUpload.write(listener.uploadBuffer.array(), offset, split);
      socketUpload.flush();

      // Send the remainder in a second packet.
      socketUpload.write(listener.uploadBuffer.array(),
          offset + split, offset + length - split);

      StatsListener newListener = runPipes(upload, download, listener.port, false);

      // Account for the retried segment.
      newListener.uploadBytes += length;
      newListener.uploadCount += 2;

      boolean success = newListener.downloadBytes > 0;

      event.putInt(Names.RETRY.name(), success ? 1 : 0);
      FirebaseAnalytics.getInstance(context).logEvent(Names.EARLY_RESET.name(), event);

      return newListener;
    } catch (IOException e) {
      // Retry failed.
      event.putInt(Names.RETRY.name(), 0);
    }
    return listener;
  }
}
