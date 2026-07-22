#!/usr/bin/env bash
set -euo pipefail

echo "=== Setting up Edge Node ==="

# 1. Update system package index and install essential dependencies
echo "--> Installing git, curl, direnv, and i2c-tools..."
sudo apt update -y
sudo apt install -y git curl direnv i2c-tools

# 2. Install Nix using Determinate Systems installer with Flakes pre-configured
if ! command -v nix &> /dev/null; then
    echo "--> Installing Nix (Flakes enabled)..."
    curl --proto '=https' --tlsv1.2 -sSf https://install.determinate.systems/nix | sh -s -- install --no-confirm
fi

# 3. Ensure Nix experimental features (nix-command and flakes) are enabled
echo "--> Configuring Nix Flakes settings..."
mkdir -p ~/.config/nix
if ! grep -q "nix-command" ~/.config/nix/nix.conf 2>/dev/null; then
    echo "extra-experimental-features = nix-command flakes" >> ~/.config/nix/nix.conf
fi

# 4. Configure shell environment hooks in ~/.bashrc
echo "--> Adding shell environment hooks to ~/.bashrc..."
if ! grep -q "direnv hook bash" ~/.bashrc 2>/dev/null; then
    echo 'eval "$(direnv hook bash)"' >> ~/.bashrc
fi

if ! grep -q "nix-profile" ~/.bashrc 2>/dev/null; then
    echo 'if [ -e ~/.nix-profile/etc/profile.d/nix.sh ]; then . ~/.nix-profile/etc/profile.d/nix.sh; fi' >> ~/.bashrc
fi

# 5. Export Nix path for current shell session
export PATH="$HOME/.nix-profile/bin:$PATH"

echo "=== Setup completed successfully! ==="
echo "Next step: Run 'direnv allow' inside the project directory."
