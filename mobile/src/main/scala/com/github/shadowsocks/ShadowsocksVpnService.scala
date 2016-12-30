/*******************************************************************************/
/*                                                                             */
/*  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          */
/*  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  */
/*                                                                             */
/*  This program is free software: you can redistribute it and/or modify       */
/*  it under the terms of the GNU General Public License as published by       */
/*  the Free Software Foundation, either version 3 of the License, or          */
/*  (at your option) any later version.                                        */
/*                                                                             */
/*  This program is distributed in the hope that it will be useful,            */
/*  but WITHOUT ANY WARRANTY; without even the implied warranty of             */
/*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              */
/*  GNU General Public License for more details.                               */
/*                                                                             */
/*  You should have received a copy of the GNU General Public License          */
/*  along with this program. If not, see <http://www.gnu.org/licenses/>.       */
/*                                                                             */
/*******************************************************************************/

package com.github.shadowsocks

import java.io.File
import java.util.Locale

import android.content._
import android.content.pm.PackageManager.NameNotFoundException
import android.net.VpnService
import android.os._
import android.util.Log
import com.github.shadowsocks.ShadowsocksApplication.app
import com.github.shadowsocks.acl.{Acl, AclSyncJob, Subnet}
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.utils._

import scala.collection.mutable.ArrayBuffer

class ShadowsocksVpnService extends VpnService with BaseService {
  val TAG = "ShadowsocksVpnService"
  val VPN_MTU = 1500
  val PRIVATE_VLAN = "26.26.26.%s"
  val PRIVATE_VLAN6 = "fdfe:dcba:9876::%s"
  var conn: ParcelFileDescriptor = _
  var vpnThread: ShadowsocksVpnThread = _
  private var notification: ShadowsocksNotification = _

  var sslocalProcess: GuardedProcess = _
  var sstunnelProcess: GuardedProcess = _
  var pdnsdProcess: GuardedProcess = _
  var tun2socksProcess: GuardedProcess = _

  override def onBind(intent: Intent): IBinder = {
    val action = intent.getAction
    if (VpnService.SERVICE_INTERFACE == action) {
      return super.onBind(intent)
    } else if (Action.SERVICE == action) {
      return binder
    }
    null
  }

  override def onRevoke() {
    stopRunner(stopService = true)
  }

  override def stopRunner(stopService: Boolean, msg: String = null) {

    if (vpnThread != null) {
      vpnThread.stopThread()
      vpnThread = null
    }

    if (notification != null) notification.destroy()

    // channge the state
    changeState(State.STOPPING)

    app.track(TAG, "stop")

    // reset VPN
    killProcesses()

    // close connections
    if (conn != null) {
      conn.close()
      conn = null
    }

    super.stopRunner(stopService, msg)
  }

  def killProcesses() {
    if (sslocalProcess != null) {
      sslocalProcess.destroy()
      sslocalProcess = null
    }
    if (sstunnelProcess != null) {
      sstunnelProcess.destroy()
      sstunnelProcess = null
    }
    if (tun2socksProcess != null) {
      tun2socksProcess.destroy()
      tun2socksProcess = null
    }
    if (pdnsdProcess != null) {
      pdnsdProcess.destroy()
      pdnsdProcess = null
    }
  }

  override def startRunner(profile: Profile) {

    // ensure the VPNService is prepared
    if (VpnService.prepare(this) != null) {
      val i = new Intent(this, classOf[ShadowsocksRunnerActivity])
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      startActivity(i)
      stopRunner(stopService = true)
      return
    }

    super.startRunner(profile)
  }

  override def connect() {
    super.connect()

    vpnThread = new ShadowsocksVpnThread(this)
    vpnThread.start()

    // reset the context
    killProcesses()

    // Resolve the server address
    if (!Utils.isNumeric(profile.host)) Utils.resolve(profile.host, enableIPv6 = true) match {
      case Some(addr) => profile.host = addr
      case None => throw NameNotResolvedException()
    }

    handleConnection()
    changeState(State.CONNECTED)

    if (profile.route != Acl.ALL && profile.route != Acl.CUSTOM_RULES)
      AclSyncJob.schedule(profile.route)

    notification = new ShadowsocksNotification(this, profile.getName)
  }

  /** Called when the activity is first created. */
  def handleConnection() {
    
    val fd = startVpn()
    if (!sendFd(fd)) throw new Exception("sendFd failed")

    startShadowsocksDaemon()

    if (!profile.udpdns) {
      startDnsDaemon()
      startDnsTunnel()
    }
  }

  override protected def buildPluginCommandLine(): ArrayBuffer[String] = super.buildPluginCommandLine() += "-V"

  def startShadowsocksProxy(
    localPort: Int = -1,
    remoteHost: String = null,
    remotePort: Int = -1
  ): GuardedProcess = {

    val cmd = ArrayBuffer[String](getApplicationInfo.nativeLibraryDir + "/libip-relay.so"
      , (if (localPort < 0) profile.localPort else localPort).toString
      , (if (remoteHost == null) profile.host else remoteHost)
      , (if (remotePort < 0) profile.remotePort else remotePort).toString
      , getFilesDir())

    return new GuardedProcess(cmd).start()
  }

  def startShadowsocksDaemon() {
    if (profile.password.length() == 0) {
      sslocalProcess = startShadowsocksProxy()
      return
    }
    val cmd = ArrayBuffer[String](getApplicationInfo.nativeLibraryDir + "/libss-local.so",
      "-V",
      "-b", "127.0.0.1",
      "-l", profile.localPort.toString,
      "-t", "600",
      "-c", buildShadowsocksConfig("ss-local-vpn.conf"))

    if (profile.udpdns) cmd += "-u"

    if (profile.route != Acl.ALL) {
      cmd += "--acl"
      cmd += Acl.getFile(profile.route).getAbsolutePath
    }

    if (TcpFastOpen.sendEnabled) cmd += "--fast-open"

    sslocalProcess = new GuardedProcess(cmd: _*).start()
  }

  def startDnsTunnel(): Unit =
    if (profile.password.length() == 0) {
      return
    }
    sstunnelProcess = new GuardedProcess(getApplicationInfo.nativeLibraryDir + "/libss-tunnel.so",
      "-V",
      "-t", "10",
      "-b", "127.0.0.1",
      "-l", (profile.localPort + 63).toString,
      "-L" , profile.remoteDns.trim + ":53",
      "-c", buildShadowsocksConfig("ss-tunnel-vpn.conf")).start()

  def startDnsDaemon() {
    val reject = if (profile.ipv6) "224.0.0.0/3" else "224.0.0.0/3, ::/0"
    val localPort = profile.localPort + (if (profile.password.length() == 0) 0 else 63)
    IOUtils.writeString(new File(getFilesDir, "pdnsd-vpn.conf"), profile.route match {
      case Acl.BYPASS_CHN | Acl.BYPASS_LAN_CHN | Acl.GFWLIST | Acl.CUSTOM_RULES =>
        ConfigUtils.PDNSD_DIRECT.formatLocal(Locale.ENGLISH, "protect = \"protect_path\";", getCacheDir.getAbsolutePath,
          "0.0.0.0", profile.localPort + 53, "114.114.114.114, 223.5.5.5, 1.2.4.8",
          getBlackList, reject, localPort, reject)
      case Acl.CHINALIST =>
        ConfigUtils.PDNSD_DIRECT.formatLocal(Locale.ENGLISH, "protect = \"protect_path\";", getCacheDir.getAbsolutePath,
          "0.0.0.0", profile.localPort + 53, "8.8.8.8, 8.8.4.4, 208.67.222.222",
          "", reject, localPort, reject)
      case _ =>
        ConfigUtils.PDNSD_LOCAL.formatLocal(Locale.ENGLISH, "protect = \"protect_path\";", getCacheDir.getAbsolutePath,
          "0.0.0.0", profile.localPort + 53, localPort, reject)
    })
    val cmd = Array(getApplicationInfo.nativeLibraryDir + "/libpdnsd.so", "-c", "pdnsd-vpn.conf")

    pdnsdProcess = new GuardedProcess(cmd: _*).start()
  }

  def startVpn(): Int = {

    val builder = new Builder()
    builder
      .setSession(profile.getName)
      .setMtu(VPN_MTU)
      .addAddress(PRIVATE_VLAN.formatLocal(Locale.ENGLISH, "1"), 24)

    builder.addDnsServer(profile.remoteDns.trim)

    if (profile.ipv6) {
      builder.addAddress(PRIVATE_VLAN6.formatLocal(Locale.ENGLISH, "1"), 126)
      builder.addRoute("::", 0)
    }

    if (Utils.isLollipopOrAbove) {

      if (profile.proxyApps) {
        for (pkg <- profile.individual.split('\n')) {
          try {
            if (!profile.bypass) {
              builder.addAllowedApplication(pkg)
            } else {
              builder.addDisallowedApplication(pkg)
            }
          } catch {
            case ex: NameNotFoundException =>
              Log.e(TAG, "Invalid package name", ex)
          }
        }
      }
    }

    if (profile.route == Acl.ALL || profile.route == Acl.BYPASS_CHN) {
      builder.addRoute("0.0.0.0", 0)
    } else {
      getResources.getStringArray(R.array.bypass_private_route).foreach(cidr => {
        val subnet = Subnet.fromString(cidr)
        builder.addRoute(subnet.address.getHostAddress, subnet.prefixSize)
      })
      builder.addRoute(profile.remoteDns.trim, 32)
    }

    conn = builder.establish()
    if (conn == null) throw new NullConnectionException

    val fd = conn.getFd

    var cmd = ArrayBuffer[String](getApplicationInfo.nativeLibraryDir + "/libtun2socks.so",
      "--netif-ipaddr", PRIVATE_VLAN.formatLocal(Locale.ENGLISH, "2"),
      "--netif-netmask", "255.255.255.0",
      "--socks-server-addr", "127.0.0.1:" + profile.localPort,
      "--tunfd", fd.toString,
      "--tunmtu", VPN_MTU.toString,
      "--sock-path", "sock_path",
      "--loglevel", "3")

    if (profile.ipv6)
      cmd += ("--netif-ip6addr", PRIVATE_VLAN6.formatLocal(Locale.ENGLISH, "2"))

    if (profile.udpdns)
      cmd += "--enable-udprelay"
    else
      cmd += ("--dnsgw", "%s:%d".formatLocal(Locale.ENGLISH, PRIVATE_VLAN.formatLocal(Locale.ENGLISH, "1"),
        profile.localPort + 53))

    tun2socksProcess = new GuardedProcess(cmd: _*).start(() => sendFd(fd))

    fd
  }

  def sendFd(fd: Int): Boolean = {
    if (fd != -1) {
      var tries = 1
      while (tries < 5) {
        Thread.sleep(1000 * tries)
        if (JniHelper.sendFd(fd, new File(getFilesDir, "sock_path").getAbsolutePath) != -1) {
          return true
        }
        tries += 1
      }
    }
    false
  }
}
