# Isolated Linux TUN integration lab

The script `scripts/linux-netns-lab.sh` creates two **temporary Linux network namespaces**, a veth underlay, two random X25519 device identities and server/client processes. It assigns tunnel addresses `10.77.0.1/30` and `10.77.0.2/30` and checks `ping` across the encrypted tunnel.

It requires Linux, root/CAP_NET_ADMIN, `iproute2`, `ping`, `timeout`, Go, and `/dev/net/tun`. Run only inside a disposable VM:

```bash
sudo bash scripts/linux-netns-lab.sh
```

The EXIT trap kills child processes, deletes both namespaces and removes temporary key files. No host default routes, DNS, or NAT are modified. The namespaces and their interfaces are disposable. It is an optional privileged **lab test**, not enabled on shared CI runners.

**Caution:** the prototype still passes secret keys in process arguments (visible to process inspection); do not use real credentials or expose it to untrusted users. Other missing production features include rekey, session lifecycle, replay cache, DoS defenses, safe management, DNS leak protection and per-client forwarding. An observed successful ping only establishes a limited point-to-point proof of concept, not VPN security.
