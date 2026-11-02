# NetherVPN Desktop

Linux desktop app is in `gui/nethervpn.py`. It is a Tkinter-based native window, not a command-line interface.

## Install
```bash
sudo apt install python3-tk policykit-1 iproute2
cd ~/NetherVPN
go mod tidy
bash scripts/install-desktop.sh
```

Search for **NetherVPN** in the application menu. Profiles live in `~/.config/nethervpn/profiles/*.conf`. The app imports profiles with restricted file permissions, starts the Go client through polkit, shows tunnel-interface counters and captures errors to the event log.

**Important limitations:** the interface exists after a completed handshake, but this alone is not a reliable indication of current peer liveness. Client reconnects after an error, but an established silent/broken session may remain unrecognized. GUI termination of the polkit-launched process has not been proven across all desktop polkit versions. It changes neither default routes nor DNS. Only test keys and isolated networks are appropriate until protocol security work and integration tests are complete.
