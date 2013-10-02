package com.redhat.qe.tools;

import com.trilead.ssh2.LocalPortForwarder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.logging.Logger;

public class ForwardedPort {

  protected static Logger log = Logger.getLogger(ForwardedPort.class.getName());

  private LocalPortForwarder forwarder;
  private int localPort;
  private String remoteHost;
  private int remotePort;

  public ForwardedPort(int remotePort, String remoteHost, int localPort,
      LocalPortForwarder forwarder) {
    this.localPort = localPort;
    this.forwarder = forwarder;
    this.remoteHost = remoteHost;
  }

  public void close() {
    try {
      forwarder.close();
    } catch (IOException e) {
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      log.warning(String.format("Failed to close %s: %s", this, sw));
    }
  }

  public int getLocalPort() {
    return localPort;
  }

  public String getRemoteHost() {
    return remoteHost;
  }

  public int getRemotePort() {
    return remotePort;
  }

  @Override
  public String toString() {
    return String.format("[ForwardedPort localPort=%d host=%s remotePort=%s]",
        localPort, remoteHost, remotePort);
  }

}
