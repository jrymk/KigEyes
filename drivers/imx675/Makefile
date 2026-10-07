# SPDX-License-Identifier: GPL-2.0-only
ifneq ($(KERNELRELEASE),)
obj-m += imx675_kigeyes.o
else
KDIR ?= /lib/modules/$(shell uname -r)/build
DTBOS := kigeyes-imx675.dtbo kigeyes-imx675-dual.dtbo \
	kigeyes-imx675-dual-sync.dtbo kigeyes-imx675-mainboard-sync.dtbo

.PHONY: all modules overlay clean install

all: modules overlay

modules:
	$(MAKE) -C $(KDIR) M=$(CURDIR) modules

overlay: $(DTBOS)


kigeyes-imx675.dtbo: overlays/kigeyes-imx675-overlay.dts
	dtc -@ -I dts -O dtb -o $@ $<

kigeyes-imx675-dual.dtbo: overlays/kigeyes-imx675-dual-overlay.dts
	dtc -@ -I dts -O dtb -o $@ $<

kigeyes-imx675-dual-sync.dtbo: overlays/kigeyes-imx675-dual-sync-overlay.dts
	dtc -@ -I dts -O dtb -o $@ $<

kigeyes-imx675-mainboard-sync.dtbo: overlays/kigeyes-imx675-mainboard-sync-overlay.dts
	dtc -@ -I dts -O dtb -o $@ $<

clean:
	$(MAKE) -C $(KDIR) M=$(CURDIR) clean
	rm -f $(DTBOS)

install: all
	install -D -m 0644 imx675_kigeyes.ko /lib/modules/$(shell uname -r)/extra/imx675_kigeyes.ko
	for dtbo in $(DTBOS); do install -D -m 0644 $$dtbo /boot/firmware/overlays/$$dtbo; done
	depmod -a
endif
