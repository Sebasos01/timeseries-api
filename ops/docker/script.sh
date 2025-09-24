# 0) Identify your Docker bridge name for the Compose network (e.g., timeseries-local_devnet)
NET="timeseries-local_devnet"
BRIDGE_NAME="$(docker network inspect "$NET" --format '{{ index .Options "com.docker.network.bridge.name" }}')"
if [ -z "$BRIDGE_NAME" ]; then
  # Fallback if bridge name option is absent: use br-<first12charsOfNetworkID>
  NET_ID="$(docker network inspect "$NET" --format '{{.ID}}' | cut -c1-12)"
  BRIDGE_NAME="br-$NET_ID"
fi
echo "$BRIDGE_NAME"

# (Optional) List all Docker bridges to confirm
ip -br link show | awk '/^br-/{print $1} /^docker0/{print $1}'

# 1) Ensure UFW is enabled and set forward policy to ACCEPT
sudo ufw status || true
echo "y" | sudo ufw enable
if [ -f /etc/default/ufw ]; then
  sudo sed -i 's/^DEFAULT_FORWARD_POLICY=.*/DEFAULT_FORWARD_POLICY="ACCEPT"/' /etc/default/ufw
fi
if [ -f /etc/ufw/ufw.conf ]; then
  sudo sed -i 's/^DEFAULT_FORWARD_POLICY=.*/DEFAULT_FORWARD_POLICY="ACCEPT"/' /etc/ufw/ufw.conf
fi

# 2) Allow routed traffic on docker0 (default bridge) — safe to add even if unused
sudo ufw route allow in on docker0
sudo ufw route allow out on docker0

# 3) Allow routed traffic on your user-defined bridge
sudo ufw route allow in on "$BRIDGE_NAME"
sudo ufw route allow out on "$BRIDGE_NAME"

# 4) Reload and verify
sudo ufw reload
sudo ufw status verbose

