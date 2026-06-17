# Installing ShareWave

Step-by-step setup for a typical deployment: the server running as a
systemd service on a Raspberry Pi (or any Linux host), with the admin GUI
installed alongside it. For configuration details, troubleshooting, and
everything else, see [README.md](README.md) — this guide only covers the
install steps.

## 1. Prerequisites

| Tool | Version |
|---|---|
| Java JDK | 17+ (21 recommended) |
| Maven | 3.8+ |

```bash
java -version
mvn -version
```

If either is missing, install a JDK (not just a JRE) and Maven via your
package manager, e.g. on Raspberry Pi OS / Debian:

```bash
sudo apt update
sudo apt install openjdk-21-jdk maven
```

## 2. Get the source

```bash
git clone <repo-url>
cd sharewave
```

## 3. Build

```bash
./build.sh
```

This builds both `sharewave-server.jar` and `sharewave-gui.jar`, and
downloads the JavaFX libraries the GUI needs into `~/.m2`.

> Run this as your **normal user**, not root/sudo — the next step relies
> on finding JavaFX in *your* `~/.m2`.

## 4. Install

```bash
sudo ./install.sh
```

This copies both JARs and the JavaFX libraries to `/opt/sharewave/`,
installs and starts `sharewave-server.service`, and sets up the
`sharewave` command for launching the admin GUI.

> If your Pi user is not named `pi`, edit `User=`/`Group=` in
> `sharewave-server.service` before running this step (or edit
> `/etc/systemd/system/sharewave-server.service` afterward and run
> `sudo systemctl daemon-reload`).

## 5. Set the admin password

The very first start needs a password typed on the console, so stop the
freshly-started service, run the JAR by hand once, then restart:

```bash
sudo systemctl stop sharewave-server
java -jar /opt/sharewave/sharewave-server.jar
# Enter a password when prompted, then press Ctrl+C
sudo systemctl start sharewave-server
```

## 6. Launch the admin GUI

From any directory, on the same machine or any machine that can reach the
server's management port (default `9443`):

```bash
sharewave
```

On first launch it asks you to set a local admin password (separate from
the server's), then prompts you to connect — enter the server's host/IP,
management port, and the password you set in step 5.

## 7. (Optional) Open the web UI to your network or the internet

By default the web UI is reachable at `https://<server-ip>:8443` from
your local network. To expose it beyond your LAN, you'll need to forward
port `8443` on your router — see "Firewall / router" in
[README.md](README.md#firewall-router-exposing-sharewave-to-the-internet).

## Updating later

Re-run steps 3 and 4 any time:

```bash
./build.sh
sudo ./install.sh
```

This is safe to repeat — it rebuilds the JARs, overwrites the previous
install, and restarts the service if it was running.

## Installing just the GUI on a different machine

If the admin GUI needs to run on a separate machine from the server, copy
the repo there and run steps 1, 3 (`./build.sh gui`), and 4. See
"Installing the GUI on another machine" in
[README.md](README.md#installing-the-gui-on-another-machine) for details.

## Something not working?

See README.md for configuration options, the uploads directory setup,
firewall details, password reset, and troubleshooting.
