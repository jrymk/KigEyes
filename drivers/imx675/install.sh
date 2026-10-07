#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-only
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OVERLAY_NAME=${IMX675_OVERLAY:-kigeyes-imx675}
OVERLAY_LINE="dtoverlay=$OVERLAY_NAME"
CONFIG=/boot/firmware/config.txt
MAINBOARD_DROPIN=/etc/systemd/system/kigeyes-imx675-display.service.d/50-mainboard.conf

cd "$SCRIPT_DIR"
make clean
make
sudo make install

# Firmware camera auto-detection may instantiate the standard camera regulator
# on CAM_GPIO0.  That signal is a 1.8 V XHS input/output on KigEyes modules, so
# prevent firmware from assigning it before Linux applies our input-only guard.
if grep -q '^camera_auto_detect=' "$CONFIG"; then
	sudo sed -i 's/^camera_auto_detect=.*/camera_auto_detect=0/' "$CONFIG"
else
	printf '\ncamera_auto_detect=0\n' | sudo tee -a "$CONFIG" >/dev/null
fi

# Keep exactly one KigEyes overlay selected when switching between direct-CM5
# development wiring and the muxed production mainboard.
sudo sed -i '/^dtoverlay=kigeyes-imx675\(-dual\(-sync\)\{0,1\}\|-mainboard-sync\)\{0,1\}$/d' "$CONFIG"
printf '\n# KigEyes IMX675 overlay (%s)\n%s\n' "$OVERLAY_NAME" "$OVERLAY_LINE" |
	sudo tee -a "$CONFIG" >/dev/null

if [ "$OVERLAY_NAME" = kigeyes-imx675-mainboard-sync ]; then
	sudo install -D -m 0644 \
		"$SCRIPT_DIR/systemd/kigeyes-imx675-mainboard.conf" \
		"$MAINBOARD_DROPIN"
else
	sudo rm -f "$MAINBOARD_DROPIN"
fi
sudo systemctl daemon-reload

printf '%s\n' \
	"Installed imx675_kigeyes.ko and the IMX675 overlays (selected: $OVERLAY_NAME)." \
	"Reboot to activate the $OVERLAY_NAME overlay."
