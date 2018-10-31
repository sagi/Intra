package app.intra.socks;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import app.intra.util.LogWrapper;
import sockslib.server.BasicSocksProxyServer;
import sockslib.server.SocksHandler;

/**
 * This is a SocksProxyServer that allows the caller to redirect UDP traffic to a fake DNS server
 * to reach the true server instead.
 */
public class SocksServer extends BasicSocksProxyServer {
  private static final String LOG_TAG = "SocksServer";

  // RFC 5382 REQ-5 requires a timeout no shorter than 2 hours and 4 minutes.
  // Sockslib's default is 10 seconds.
  private static final int TIMEOUT_MS = 1000 * 60 * (4 + 60 * 2);

  private final InetSocketAddress fakeDns;
  private final InetSocketAddress trueDns;

  private final Context context;

  SocksServer(Context context, InetSocketAddress fakeDns, InetSocketAddress trueDns) {
    super(OverrideSocksHandler.class);
    this.fakeDns = fakeDns;
    this.trueDns = trueDns;
    this.context = context;
    setTimeout(TIMEOUT_MS);
  }

  @Override
  public void initializeSocksHandler(SocksHandler socksHandler) {
    super.initializeSocksHandler(socksHandler);
    if (socksHandler instanceof OverrideSocksHandler) {
      OverrideSocksHandler override = (OverrideSocksHandler)socksHandler;
      override.setDns(fakeDns, trueDns);
      override.setContext(context);
    } else {
      LogWrapper.logcat(Log.WARN, LOG_TAG, "Foreign handler");
    }
  }

  @Override
  protected ServerSocket createServerSocket(int bindPort, InetAddress bindAddr) throws IOException {
    // Workaround for upstream's lack of support for port 0.  TODO: Add this capability to Sockslib.
    ServerSocket serverSocket = super.createServerSocket(bindPort, bindAddr);
    this.setBindPort(serverSocket.getLocalPort());
    return serverSocket;
  }
}
