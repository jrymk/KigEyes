// SPDX-License-Identifier: GPL-2.0
/*
 * KigEyes Sony IMX675 camera sensor driver
 *
 * The camera module contains a CH32V003 management controller which owns the
 * sensor rails, XCLR, the 24 MHz oscillator enable, and XMASTER.  Linux talks
 * to that controller at 0x30 and to the IMX675 itself at 0x1a.
 */

#include <linux/delay.h>
#include <linux/i2c.h>
#include <linux/media-bus-format.h>
#include <linux/module.h>
#include <linux/pm_runtime.h>
#include <linux/property.h>

#include <media/v4l2-cci.h>
#include <media/v4l2-ctrls.h>
#include <media/v4l2-device.h>
#include <media/v4l2-fwnode.h>
#include <media/v4l2-mediabus.h>
#include <media/v4l2-subdev.h>

#include "imx675_regs.h"

#define IMX675_NATIVE_WIDTH		2608U
/* Bench comparison: independent timing without driving the shared sync bus
 * from a device wired as a slave. Read-only; change only on module reload. */
static bool free_run;
module_param(free_run, bool, 0444);
MODULE_PARM_DESC(free_run, "Independent sensor timing; DT slaves retain high-Z sync outputs");
#define IMX675_NATIVE_HEIGHT		1964U
#define IMX675_BINNING_WIDTH		1304U
#define IMX675_BINNING_HEIGHT		982U
#define IMX675_SOURCE_PAD		0

#define IMX675_PIXEL_RATE		594000000LL
#define IMX675_PIXELS_PER_HMAX		8U

#define IMX675_VMAX_MAX			0xfffffU
#define IMX675_SHR_MIN			4U
#define IMX675_EXPOSURE_MIN		1U
#define IMX675_GAIN_MAX			240U
#define IMX675_GAIN_DEFAULT		80U

#define IMX675_CONTROLLER_ADDR		0x30
#define IMX675_CONTROLLER_TIMEOUT_MS	600

/* KigEyes camera-module controller protocol, version 1. */
#define CAMERA_REG_MAGIC0		0x00
#define CAMERA_REG_MAGIC1		0x01
#define CAMERA_REG_PROTOCOL_MAJOR	0x02
#define CAMERA_REG_MODULE_VARIANT	0x04
#define CAMERA_REG_FW_MAJOR		0x05
#define CAMERA_REG_STATE		0x0b
#define CAMERA_REG_STATUS_FLAGS		0x0c
#define CAMERA_REG_FAULT		0x0d
#define CAMERA_REG_COMMAND_RESULT	0x0e
#define CAMERA_REG_COMPLETED_SEQUENCE	0x10
#define CAMERA_REG_COMMAND		0x40
#define CAMERA_REG_COMMAND_SEQUENCE	0x42
#define CAMERA_REG_MODE_CONTROL		0x43
#define CAMERA_REG_HOST_CONTROL		0x44

#define CAMERA_PROTOCOL_MAJOR		1
#define CAMERA_MODULE_IMX675		0x75

#define CAMERA_STATE_OFF		0
#define CAMERA_STATE_ON			7
#define CAMERA_STATE_FAULT		15

#define CAMERA_STATUS_POWERED		BIT(1)
#define CAMERA_STATUS_READY		BIT(2)
#define CAMERA_STATUS_CLOCK_ENABLED	BIT(4)

#define CAMERA_MODE_MASTER		0
#define CAMERA_MODE_SLAVE		1
#define CAMERA_HOST_QUIESCED_TOKEN	0xa5

#define CAMERA_COMMAND_POWER_ON		1
#define CAMERA_COMMAND_POWER_OFF	2
#define CAMERA_COMMAND_POWER_OFF_FORCE	3
#define CAMERA_COMMAND_CLEAR_FAULT	5

#define CAMERA_RESULT_OK		2

enum imx675_link_freq_index {
	IMX675_LINK_FREQ_1188,
	IMX675_LINK_FREQ_594,
};

static const s64 imx675_link_freqs[] = {
	[IMX675_LINK_FREQ_1188] = 594000000LL,
	[IMX675_LINK_FREQ_594] = 297000000LL,
};

struct imx675_mode {
	u32 width;
	u32 height;
	u32 hmax;
	u32 vmax_min;
	u32 vmax_default;
	u32 exposure_default;
	u8 link_freq_index;
	u8 datarate_sel;
	u8 addmode;
	u8 adbit;
	u8 mdbit;
	u8 binning_timing;
};

/*
 * Full-pixel mode uses 1188 Mbit/s/lane; H2V2 mode uses 594 Mbit/s/lane.
 * Both use a 594 MHz V4L2 pixel rate. HMAX is converted to the V4L2 line
 * length with IMX675_PIXELS_PER_HMAX.
 *
 * In H2V2 mode VMAX remains expressed in native sensor row periods, so the
 * minimum frame length is the native 1964 rows plus 70 blanking rows.  A
 * default VMAX of 5476 gives almost exactly 30 fps; reducing VBLANK exposes
 * the sensor's approximately 80 fps maximum for this mode.
 */
static const struct imx675_mode imx675_modes[] = {
	{
		.width = IMX675_NATIVE_WIDTH,
		.height = IMX675_NATIVE_HEIGHT,
		.hmax = 602,
		.vmax_min = 2052,
		.vmax_default = 2052,
		.exposure_default = 2000,
		.link_freq_index = IMX675_LINK_FREQ_1188,
		.datarate_sel = 4,
		.addmode = 0,
		.adbit = 1,
		.mdbit = 1,
		.binning_timing = 0,
	},
	{
		.width = IMX675_BINNING_WIDTH,
		.height = IMX675_BINNING_HEIGHT,
		.hmax = 452,
		.vmax_min = 2034,
		.vmax_default = 5476,
		.exposure_default = 5000,
		.link_freq_index = IMX675_LINK_FREQ_594,
		.datarate_sel = 7,
		.addmode = 1,
		.adbit = 0,
		.mdbit = 1,
		.binning_timing = 4,
	},
};

struct imx675 {
	struct v4l2_subdev sd;
	struct media_pad pad;
	struct regmap *cci;

	struct i2c_client *controller;

	struct v4l2_ctrl_handler ctrl_handler;
	struct v4l2_ctrl *exposure;
	struct v4l2_ctrl *vblank;
	struct v4l2_ctrl *hblank;
	struct v4l2_ctrl *link_freq;
	const struct imx675_mode *mode;
	u32 vmax;
    bool sync_slave;
    bool timing_slave;
};

static inline struct imx675 *to_imx675(struct v4l2_subdev *sd)
{
	return container_of_const(sd, struct imx675, sd);
}

static int imx675_controller_read(struct imx675 *imx675, u8 reg,
				  void *data, size_t len)
{
	struct i2c_msg msgs[2] = {
		{
			.addr = imx675->controller->addr,
			.flags = 0,
			.len = 1,
			.buf = &reg,
		},
		{
			.addr = imx675->controller->addr,
			.flags = I2C_M_RD,
			.len = len,
			.buf = data,
		},
	};
	int ret;

	ret = i2c_transfer(imx675->controller->adapter, msgs, ARRAY_SIZE(msgs));
	if (ret == ARRAY_SIZE(msgs))
		return 0;

	return ret < 0 ? ret : -EIO;
}

static int imx675_controller_write(struct imx675 *imx675, u8 reg, u8 value)
{
	u8 data[] = { reg, value };
	int ret;

	ret = i2c_master_send(imx675->controller, data, sizeof(data));
	if (ret == sizeof(data))
		return 0;

	return ret < 0 ? ret : -EIO;
}

static int imx675_controller_command(struct imx675 *imx675, u8 command)
{
	struct device *dev = &imx675->controller->dev;
	u8 status[4];
	u8 completed;
	u8 sequence;
	int timeout;
	int ret;

	ret = imx675_controller_read(imx675,
				      CAMERA_REG_COMPLETED_SEQUENCE,
				      &completed, sizeof(completed));
	if (ret)
		return ret;

	sequence = completed + 1;
	ret = imx675_controller_write(imx675, CAMERA_REG_COMMAND_SEQUENCE,
				       sequence);
	if (ret)
		return ret;

	ret = imx675_controller_write(imx675, CAMERA_REG_COMMAND, command);
	if (ret)
		return ret;

	for (timeout = 0; timeout < IMX675_CONTROLLER_TIMEOUT_MS; timeout += 5) {
		/* result, accepted sequence, completed sequence, active command */
		ret = imx675_controller_read(imx675,
					      CAMERA_REG_COMMAND_RESULT,
					      status, sizeof(status));
		if (ret)
			return ret;

		if (status[2] == sequence) {
			if (status[0] == CAMERA_RESULT_OK)
				return 0;

			dev_err(dev,
				"command %u failed: result=%u active=%u\n",
				command, status[0], status[3]);
			return -EIO;
		}

		usleep_range(5000, 6000);
	}

	dev_err(dev, "command %u timed out\n", command);
	return -ETIMEDOUT;
}

static int imx675_controller_identify(struct imx675 *imx675)
{
	struct device *dev = &imx675->controller->dev;
	u8 info[8];
	int ret;

	ret = imx675_controller_read(imx675, CAMERA_REG_MAGIC0,
				      info, sizeof(info));
	if (ret)
		return dev_err_probe(dev, ret,
				     "camera controller did not respond\n");

	if (info[0] != 'K' || info[1] != 'E' ||
	    info[2] != CAMERA_PROTOCOL_MAJOR ||
	    info[4] != CAMERA_MODULE_IMX675) {
		dev_err(dev,
			"unexpected controller identity %02x %02x protocol %u variant 0x%02x\n",
			info[0], info[1], info[2], info[4]);
		return -ENODEV;
	}

	dev_dbg(dev, "camera controller firmware %u.%u.%u\n",
		info[5], info[6], info[7]);
	return 0;
}

static int imx675_write_configuration(struct imx675 *imx675)
{
	const struct imx675_mode *mode = imx675->mode;
	int ret = 0;
	unsigned int i;

	cci_multi_reg_write(imx675->cci, imx675_recommended_regs,
			    ARRAY_SIZE(imx675_recommended_regs), &ret);

	/* 24 MHz input, mode-specific data rate, RAW12 output, four lanes. */
	cci_write(imx675->cci, IMX675_REG_INCK_SEL, 4, &ret);
	cci_write(imx675->cci, IMX675_REG_DATARATE_SEL,
		  mode->datarate_sel, &ret);
	cci_write(imx675->cci, IMX675_REG_WINMODE, 0, &ret);
	cci_write(imx675->cci, IMX675_REG_ADDMODE, mode->addmode, &ret);
	cci_write(imx675->cci, IMX675_REG_HREVERSE, 0, &ret);
	cci_write(imx675->cci, IMX675_REG_VREVERSE, 0, &ret);
	cci_write(imx675->cci, IMX675_REG_ADBIT, mode->adbit, &ret);
	cci_write(imx675->cci, IMX675_REG_MDBIT, mode->mdbit, &ret);
	cci_write(imx675->cci, IMX675_REG_VMAX, imx675->vmax, &ret);
	cci_write(imx675->cci, IMX675_REG_HMAX, mode->hmax, &ret);
	cci_write(imx675->cci, IMX675_REG_LANEMODE, 3, &ret);
	cci_write(imx675->cci, IMX675_REG_SHR0,
		  imx675->vmax - mode->exposure_default, &ret);
	/* SRM: XVS is bits [1:0], XHS is bits [3:2] (not [5:4]).
	 * Select VSYNC/HSYNC (2 each); slaves must tri-state BOTH outputs.
	 * 0x22/0x33 would leave XHS constant-low/driven and break slave timing.
	 */
	cci_write(imx675->cci, IMX675_REG_XVS_XHS_OUTSEL, 0x0a, &ret);
	cci_write(imx675->cci, IMX675_REG_XVS_XHS_DRV,
		  imx675->sync_slave ? 0x0f : 0x00, &ret);

	for (i = 0; i < 6; ++i)
		cci_write(imx675->cci, IMX675_REG_BINNING_TIMING(i),
			  mode->binning_timing, &ret);

	return ret;
}

static int imx675_power_on(struct device *dev)
{
	struct i2c_client *client = to_i2c_client(dev);
	struct v4l2_subdev *sd = i2c_get_clientdata(client);
	struct imx675 *imx675 = to_imx675(sd);
	u8 controller_status[3];
	int ret;

	ret = imx675_controller_identify(imx675);
	if (ret)
		return ret;

	ret = imx675_controller_read(imx675, CAMERA_REG_STATE,
				      controller_status,
				      sizeof(controller_status));
	if (ret)
		return ret;

	if (controller_status[0] == CAMERA_STATE_FAULT) {
		ret = imx675_controller_command(imx675,
					       CAMERA_COMMAND_CLEAR_FAULT);
		if (ret)
			goto force_off;
	}

	ret = imx675_controller_write(imx675, CAMERA_REG_MODE_CONTROL,
				       imx675->timing_slave ? CAMERA_MODE_SLAVE :
				       CAMERA_MODE_MASTER);
	if (ret)
		goto force_off;

	ret = imx675_controller_write(imx675, CAMERA_REG_HOST_CONTROL, 0);
	if (ret)
		goto force_off;

	ret = imx675_controller_command(imx675, CAMERA_COMMAND_POWER_ON);
	if (ret)
		goto force_off;

	ret = imx675_controller_read(imx675, CAMERA_REG_STATE,
				      controller_status,
				      sizeof(controller_status));
	if (ret)
		goto force_off;

	if (controller_status[0] != CAMERA_STATE_ON ||
	    (controller_status[1] & (CAMERA_STATUS_POWERED |
				     CAMERA_STATUS_READY |
				     CAMERA_STATUS_CLOCK_ENABLED)) !=
	    (CAMERA_STATUS_POWERED | CAMERA_STATUS_READY |
	     CAMERA_STATUS_CLOCK_ENABLED)) {
		dev_err(dev, "controller did not reach ready state: state=%u flags=0x%02x fault=%u\n",
			controller_status[0], controller_status[1],
			controller_status[2]);
		ret = -EIO;
		goto force_off;
	}

	ret = imx675_write_configuration(imx675);
	if (ret) {
		dev_err(dev, "failed to configure sensor: %d\n", ret);
		goto force_off;
	}

	return 0;

force_off:
	if (imx675_controller_command(imx675,
				      CAMERA_COMMAND_POWER_OFF_FORCE))
		dev_warn(dev, "failed to force camera controller off\n");
	return ret;
}

static int imx675_power_off(struct device *dev)
{
	struct i2c_client *client = to_i2c_client(dev);
	struct v4l2_subdev *sd = i2c_get_clientdata(client);
	struct imx675 *imx675 = to_imx675(sd);
	int ret = 0;
	int command_ret;

	/* The host must place the sensor in standby before removing its rails. */
	if (!imx675->timing_slave)
		cci_write(imx675->cci, IMX675_REG_XMSTA, 1, &ret);
	cci_write(imx675->cci, IMX675_REG_STANDBY, 1, &ret);
	if (ret)
		dev_warn(dev, "failed to place sensor in standby: %d\n", ret);
	else
		msleep(40);

	/* Never claim quiescence if standby could not be confirmed. */
	command_ret = ret;
	if (!command_ret)
		command_ret = imx675_controller_write(imx675,
					       CAMERA_REG_HOST_CONTROL,
					       CAMERA_HOST_QUIESCED_TOKEN);
	if (!command_ret)
		command_ret = imx675_controller_command(imx675,
						       CAMERA_COMMAND_POWER_OFF);

	if (command_ret) {
		dev_warn(dev, "guarded power-off failed (%d), forcing safe outputs\n",
			 command_ret);
		command_ret = imx675_controller_command(imx675,
						CAMERA_COMMAND_POWER_OFF_FORCE);
		if (command_ret)
			dev_warn(dev, "forced power-off command also failed\n");
	}

	/* Keep runtime PM active on failure so shutdown can retry rail-off. */
	return command_ret;
}

static int imx675_set_ctrl(struct v4l2_ctrl *ctrl)
{
	struct imx675 *imx675 = container_of_const(ctrl->handler,
						   struct imx675, ctrl_handler);
	struct i2c_client *client = v4l2_get_subdevdata(&imx675->sd);
	int ret = 0;

	if (ctrl->id == V4L2_CID_VBLANK) {
		u32 exposure;

		imx675->vmax = imx675->mode->height + ctrl->val;
		exposure = clamp_t(u32, imx675->exposure->cur.val,
				   IMX675_EXPOSURE_MIN,
				   imx675->vmax - IMX675_SHR_MIN);
		ret = __v4l2_ctrl_modify_range(imx675->exposure,
					       IMX675_EXPOSURE_MIN,
					       imx675->vmax - IMX675_SHR_MIN,
					       1, exposure);
		if (ret)
			return ret;
	}

	if (!pm_runtime_get_if_in_use(&client->dev))
		return 0;

	switch (ctrl->id) {
	case V4L2_CID_VBLANK:
		cci_write(imx675->cci, IMX675_REG_VMAX, imx675->vmax, &ret);
		fallthrough;
	case V4L2_CID_EXPOSURE:
		cci_write(imx675->cci, IMX675_REG_SHR0,
			  imx675->vmax - imx675->exposure->val, &ret);
		break;
	case V4L2_CID_ANALOGUE_GAIN:
		cci_write(imx675->cci, IMX675_REG_GAIN, ctrl->val, &ret);
		break;
	default:
		break;
	}

	pm_runtime_put(&client->dev);
	return ret;
}

static const struct v4l2_ctrl_ops imx675_ctrl_ops = {
	.s_ctrl = imx675_set_ctrl,
};

static int imx675_update_controls(struct imx675 *imx675,
				  const struct imx675_mode *mode)
{
	u32 hblank = mode->hmax * IMX675_PIXELS_PER_HMAX - mode->width;
	u32 vblank_min = mode->vmax_min - mode->height;
	u32 vblank_default = mode->vmax_default - mode->height;
	u32 exposure_max = mode->vmax_default - IMX675_SHR_MIN;
	int ret;

	ret = __v4l2_ctrl_s_ctrl(imx675->link_freq,
				    mode->link_freq_index);
	if (ret)
		return ret;

	ret = __v4l2_ctrl_modify_range(imx675->hblank, hblank, hblank, 1,
				       hblank);
	if (ret)
		return ret;

	ret = __v4l2_ctrl_modify_range(imx675->vblank, vblank_min,
				       IMX675_VMAX_MAX - mode->height, 2,
				       vblank_default);
	if (ret)
		return ret;

	ret = __v4l2_ctrl_s_ctrl(imx675->vblank, vblank_default);
	if (ret)
		return ret;

	ret = __v4l2_ctrl_modify_range(imx675->exposure,
				       IMX675_EXPOSURE_MIN, exposure_max, 1,
				       min_t(u32, mode->exposure_default,
					     exposure_max));
	if (ret)
		return ret;

	return __v4l2_ctrl_s_ctrl(imx675->exposure,
				    min_t(u32, mode->exposure_default,
					  exposure_max));
}

static int imx675_init_controls(struct imx675 *imx675)
{
	struct i2c_client *client = v4l2_get_subdevdata(&imx675->sd);
	const struct imx675_mode *mode = imx675->mode;
	struct v4l2_fwnode_device_properties props;
	struct v4l2_ctrl_handler *handler = &imx675->ctrl_handler;
	u32 hblank = mode->hmax * IMX675_PIXELS_PER_HMAX - mode->width;
	u32 vblank_min = mode->vmax_min - mode->height;
	u32 vblank_default = mode->vmax_default - mode->height;
	int ret;

	ret = v4l2_fwnode_device_parse(&client->dev, &props);
	if (ret)
		return ret;

	ret = v4l2_ctrl_handler_init(handler, 8);
	if (ret)
		return ret;

	imx675->vmax = mode->vmax_default;

	v4l2_ctrl_new_std(handler, &imx675_ctrl_ops, V4L2_CID_PIXEL_RATE,
			  IMX675_PIXEL_RATE, IMX675_PIXEL_RATE, 1,
			  IMX675_PIXEL_RATE);

	imx675->link_freq = v4l2_ctrl_new_int_menu(handler, &imx675_ctrl_ops,
						  V4L2_CID_LINK_FREQ,
						  ARRAY_SIZE(imx675_link_freqs) - 1,
						  0, imx675_link_freqs);
	if (imx675->link_freq)
		imx675->link_freq->flags |= V4L2_CTRL_FLAG_READ_ONLY;

	imx675->vblank = v4l2_ctrl_new_std(handler, &imx675_ctrl_ops,
					  V4L2_CID_VBLANK, vblank_min,
					  IMX675_VMAX_MAX - mode->height,
					  2, vblank_default);

	imx675->hblank = v4l2_ctrl_new_std(handler, &imx675_ctrl_ops,
					  V4L2_CID_HBLANK,
					  hblank, hblank, 1, hblank);
	if (imx675->hblank)
		imx675->hblank->flags |= V4L2_CTRL_FLAG_READ_ONLY;

	imx675->exposure = v4l2_ctrl_new_std(handler, &imx675_ctrl_ops,
					    V4L2_CID_EXPOSURE,
					    IMX675_EXPOSURE_MIN,
					    mode->vmax_default -
					    IMX675_SHR_MIN,
					    1,
					    mode->exposure_default);

	v4l2_ctrl_new_std(handler, &imx675_ctrl_ops, V4L2_CID_ANALOGUE_GAIN,
			  0, IMX675_GAIN_MAX, 1, IMX675_GAIN_DEFAULT);

	v4l2_ctrl_new_fwnode_properties(handler, &imx675_ctrl_ops, &props);

	if (handler->error) {
		ret = handler->error;
		v4l2_ctrl_handler_free(handler);
		return dev_err_probe(&client->dev, ret,
				     "failed to initialize controls\n");
	}

	imx675->sd.ctrl_handler = handler;
	return 0;
}

static int imx675_enum_mbus_code(struct v4l2_subdev *sd,
				 struct v4l2_subdev_state *state,
				 struct v4l2_subdev_mbus_code_enum *code)
{
	if (code->index)
		return -EINVAL;

	code->code = MEDIA_BUS_FMT_SRGGB12_1X12;
	return 0;
}

static int imx675_enum_frame_size(struct v4l2_subdev *sd,
				  struct v4l2_subdev_state *state,
				  struct v4l2_subdev_frame_size_enum *fse)
{
	if (fse->index >= ARRAY_SIZE(imx675_modes) ||
	    fse->code != MEDIA_BUS_FMT_SRGGB12_1X12)
		return -EINVAL;

	fse->min_width = imx675_modes[fse->index].width;
	fse->max_width = fse->min_width;
	fse->min_height = imx675_modes[fse->index].height;
	fse->max_height = fse->min_height;
	return 0;
}

static void imx675_fill_format(const struct imx675_mode *mode,
			       struct v4l2_mbus_framefmt *format)
{
	format->code = MEDIA_BUS_FMT_SRGGB12_1X12;
	format->width = mode->width;
	format->height = mode->height;
	format->field = V4L2_FIELD_NONE;
	format->colorspace = V4L2_COLORSPACE_RAW;
	format->ycbcr_enc = V4L2_YCBCR_ENC_DEFAULT;
	format->quantization = V4L2_QUANTIZATION_FULL_RANGE;
	format->xfer_func = V4L2_XFER_FUNC_NONE;
}

static int imx675_set_fmt(struct v4l2_subdev *sd,
			  struct v4l2_subdev_state *state,
			  struct v4l2_subdev_format *fmt)
{
	struct imx675 *imx675 = to_imx675(sd);
	struct v4l2_mbus_framefmt *format;
	const struct imx675_mode *mode;

	if (fmt->pad != IMX675_SOURCE_PAD)
		return -EINVAL;

	mode = v4l2_find_nearest_size(imx675_modes,
				      ARRAY_SIZE(imx675_modes), width, height,
				      fmt->format.width, fmt->format.height);
	imx675_fill_format(mode, &fmt->format);

	format = v4l2_subdev_state_get_format(state, IMX675_SOURCE_PAD);
	*format = fmt->format;

	if (fmt->which == V4L2_SUBDEV_FORMAT_TRY)
		return 0;

	if (imx675->mode == mode)
		return 0;

	imx675->mode = mode;
	return imx675_update_controls(imx675, mode);
}

static int imx675_get_selection(struct v4l2_subdev *sd,
				struct v4l2_subdev_state *state,
				struct v4l2_subdev_selection *sel)
{
	if (sel->pad != IMX675_SOURCE_PAD)
		return -EINVAL;

	switch (sel->target) {
	case V4L2_SEL_TGT_NATIVE_SIZE:
	case V4L2_SEL_TGT_CROP_BOUNDS:
	case V4L2_SEL_TGT_CROP_DEFAULT:
	case V4L2_SEL_TGT_CROP:
		sel->r.left = 0;
		sel->r.top = 0;
		sel->r.width = IMX675_NATIVE_WIDTH;
		sel->r.height = IMX675_NATIVE_HEIGHT;
		return 0;
	default:
		return -EINVAL;
	}
}

static int imx675_init_state(struct v4l2_subdev *sd,
			     struct v4l2_subdev_state *state)
{
	struct v4l2_mbus_framefmt *format;

	format = v4l2_subdev_state_get_format(state, IMX675_SOURCE_PAD);
	imx675_fill_format(&imx675_modes[0], format);
	return 0;
}

static int imx675_enable_streams(struct v4l2_subdev *sd,
				 struct v4l2_subdev_state *state,
				 u32 pad, u64 streams_mask)
{
	struct i2c_client *client = v4l2_get_subdevdata(sd);
	struct imx675 *imx675 = to_imx675(sd);
	int ret;

	ret = pm_runtime_resume_and_get(&client->dev);
	if (ret < 0)
		return ret;

	ret = __v4l2_ctrl_handler_setup(imx675->sd.ctrl_handler);
	if (ret)
		goto error_put;

	cci_write(imx675->cci, IMX675_REG_STANDBY, 0, &ret);
	if (!ret)
		msleep(24);
	if (!imx675->timing_slave)
		cci_write(imx675->cci, IMX675_REG_XMSTA, 0, &ret);
	if (ret) {
		dev_err(&client->dev, "failed to start streaming: %d\n", ret);
		goto error_put;
	}

	return 0;

error_put:
	pm_runtime_put(&client->dev);
	return ret;
}

static int imx675_disable_streams(struct v4l2_subdev *sd,
				  struct v4l2_subdev_state *state,
				  u32 pad, u64 streams_mask)
{
	struct i2c_client *client = v4l2_get_subdevdata(sd);
	struct imx675 *imx675 = to_imx675(sd);
	int ret = 0;

	if (!imx675->timing_slave)
		cci_write(imx675->cci, IMX675_REG_XMSTA, 1, &ret);
	if (!ret && !imx675->timing_slave)
		msleep(40);
	cci_write(imx675->cci, IMX675_REG_STANDBY, 1, &ret);
	if (ret)
		dev_err(&client->dev, "failed to stop streaming: %d\n", ret);

	pm_runtime_put(&client->dev);
	return ret;
}

static const struct v4l2_subdev_video_ops imx675_video_ops = {
	.s_stream = v4l2_subdev_s_stream_helper,
};

static const struct v4l2_subdev_pad_ops imx675_pad_ops = {
	.enum_mbus_code = imx675_enum_mbus_code,
	.get_fmt = v4l2_subdev_get_fmt,
	.set_fmt = imx675_set_fmt,
	.enum_frame_size = imx675_enum_frame_size,
	.get_selection = imx675_get_selection,
	.enable_streams = imx675_enable_streams,
	.disable_streams = imx675_disable_streams,
};

static const struct v4l2_subdev_ops imx675_subdev_ops = {
	.video = &imx675_video_ops,
	.pad = &imx675_pad_ops,
};

static const struct v4l2_subdev_internal_ops imx675_internal_ops = {
	.init_state = imx675_init_state,
};

static int imx675_check_hwcfg(struct device *dev)
{
	struct v4l2_fwnode_endpoint endpoint_cfg = {
		.bus_type = V4L2_MBUS_CSI2_DPHY,
	};
	struct fwnode_handle *endpoint;
	bool found_frequency[ARRAY_SIZE(imx675_link_freqs)] = {};
	unsigned int i, j;
	int ret;

	endpoint = fwnode_graph_get_endpoint_by_id(dev_fwnode(dev), 0, 0, 0);
	if (!endpoint)
		return dev_err_probe(dev, -EINVAL, "endpoint node not found\n");

	ret = v4l2_fwnode_endpoint_alloc_parse(endpoint, &endpoint_cfg);
	fwnode_handle_put(endpoint);
	if (ret)
		return dev_err_probe(dev, ret, "could not parse endpoint\n");

	if (endpoint_cfg.bus.mipi_csi2.num_data_lanes != 4) {
		dev_err(dev, "exactly four CSI-2 data lanes are required\n");
		ret = -EINVAL;
		goto out_free_endpoint;
	}

	for (i = 0; i < endpoint_cfg.nr_of_link_frequencies; i++) {
		for (j = 0; j < ARRAY_SIZE(imx675_link_freqs); j++)
			if (endpoint_cfg.link_frequencies[i] ==
			    imx675_link_freqs[j])
				found_frequency[j] = true;
	}

	for (i = 0; i < ARRAY_SIZE(found_frequency); i++) {
		if (!found_frequency[i]) {
			dev_err(dev, "missing required link frequency %lld Hz\n",
				imx675_link_freqs[i]);
			ret = -EINVAL;
			break;
		}
	}

out_free_endpoint:
	v4l2_fwnode_endpoint_free(&endpoint_cfg);
	return ret;
}

static int imx675_probe(struct i2c_client *client)
{
	struct device *dev = &client->dev;
	struct imx675 *imx675;
	const char *sync_role;
	u32 controller_address = IMX675_CONTROLLER_ADDR;
	u64 standby;
	int ret;

	imx675 = devm_kzalloc(dev, sizeof(*imx675), GFP_KERNEL);
	if (!imx675)
		return -ENOMEM;
	imx675->mode = &imx675_modes[0];
	imx675->vmax = imx675->mode->vmax_default;

	if (!device_property_read_string(dev, "kigeyes,sync-role", &sync_role)) {
		if (!strcmp(sync_role, "slave"))
			imx675->sync_slave = true;
		else if (strcmp(sync_role, "master"))
			return dev_err_probe(dev, -EINVAL,
					     "invalid sync role '%s'\n", sync_role);
	}

	imx675->timing_slave = imx675->sync_slave && !free_run;
	v4l2_i2c_subdev_init(&imx675->sd, client, &imx675_subdev_ops);

	imx675->cci = devm_cci_regmap_init_i2c(client, 16);
	if (IS_ERR(imx675->cci))
		return dev_err_probe(dev, PTR_ERR(imx675->cci),
				     "failed to initialize CCI\n");

	ret = imx675_check_hwcfg(dev);
	if (ret)
		return ret;

	device_property_read_u32(dev, "kigeyes,controller-address",
				 &controller_address);
	if (controller_address > 0x7f || controller_address == client->addr)
		return dev_err_probe(dev, -EINVAL,
				     "invalid controller address 0x%x\n",
				     controller_address);

	imx675->controller = devm_i2c_new_dummy_device(dev, client->adapter,
						       controller_address);
	if (IS_ERR(imx675->controller))
		return dev_err_probe(dev, PTR_ERR(imx675->controller),
				     "failed to reserve camera controller address\n");

	ret = imx675_power_on(dev);
	if (ret)
		return ret;

	ret = 0;
	cci_read(imx675->cci, IMX675_REG_STANDBY, &standby, &ret);
	if (ret) {
		dev_err(dev, "IMX675 did not respond after power-up: %d\n", ret);
		goto error_power_off;
	}

	if (standby != 1)
		dev_warn(dev, "unexpected initial standby value 0x%llx\n", standby);

	pm_runtime_set_active(dev);
	pm_runtime_enable(dev);

	ret = imx675_init_controls(imx675);
	if (ret)
		goto error_pm_runtime;

	imx675->sd.internal_ops = &imx675_internal_ops;
	imx675->sd.flags |= V4L2_SUBDEV_FL_HAS_DEVNODE;
	imx675->sd.entity.function = MEDIA_ENT_F_CAM_SENSOR;
	imx675->pad.flags = MEDIA_PAD_FL_SOURCE;

	ret = media_entity_pads_init(&imx675->sd.entity, 1, &imx675->pad);
	if (ret)
		goto error_handler_free;

	imx675->sd.state_lock = imx675->ctrl_handler.lock;
	ret = v4l2_subdev_init_finalize(&imx675->sd);
	if (ret)
		goto error_media_entity;

	ret = v4l2_async_register_subdev_sensor(&imx675->sd);
	if (ret)
		goto error_subdev_cleanup;

	dev_info(dev, "KigEyes IMX675 detected (2 RAW12 modes, 4 lanes, sync %s)\n",
		 imx675->sync_slave ? "slave" : "master");
	pm_runtime_idle(dev);
	return 0;

error_subdev_cleanup:
	v4l2_subdev_cleanup(&imx675->sd);
error_media_entity:
	media_entity_cleanup(&imx675->sd.entity);
error_handler_free:
	v4l2_ctrl_handler_free(&imx675->ctrl_handler);
error_pm_runtime:
	pm_runtime_disable(dev);
	pm_runtime_set_suspended(dev);
error_power_off:
	imx675_power_off(dev);
	return ret;
}

static void imx675_remove(struct i2c_client *client)
{
	struct v4l2_subdev *sd = i2c_get_clientdata(client);
	struct imx675 *imx675 = to_imx675(sd);

	v4l2_async_unregister_subdev(sd);
	v4l2_subdev_cleanup(sd);
	media_entity_cleanup(&sd->entity);
	v4l2_ctrl_handler_free(&imx675->ctrl_handler);

	pm_runtime_disable(&client->dev);
	if (!pm_runtime_status_suspended(&client->dev))
		imx675_power_off(&client->dev);
	pm_runtime_set_suspended(&client->dev);
}

static void imx675_shutdown(struct i2c_client *client)
{
	struct device *dev = &client->dev;

	/* Halt/reboot does not call remove(). The mainboard can keep supplying
	 * power after Linux halts, so sensor standby alone is not sufficient.
	 * Drain runtime PM before touching the controller; do not resume an
	 * already powered-down camera just to shut it down again. The I2C mux
	 * and adapter parents are still available during this child callback.
	 */
	pm_runtime_disable(dev);
	if (!pm_runtime_status_suspended(dev))
		imx675_power_off(dev);
	pm_runtime_set_suspended(dev);
}

static const struct dev_pm_ops imx675_pm_ops = {
	SET_RUNTIME_PM_OPS(imx675_power_off, imx675_power_on, NULL)
};

static const struct of_device_id imx675_of_match[] = {
	{ .compatible = "kigeyes,imx675" },
	{ }
};
MODULE_DEVICE_TABLE(of, imx675_of_match);

static struct i2c_driver imx675_i2c_driver = {
	.driver = {
		.name = "imx675",
		.of_match_table = imx675_of_match,
		.pm = pm_ptr(&imx675_pm_ops),
	},
	.probe = imx675_probe,
	.remove = imx675_remove,
	.shutdown = imx675_shutdown,
};
module_i2c_driver(imx675_i2c_driver);

MODULE_AUTHOR("KigEyes project");
MODULE_DESCRIPTION("KigEyes Sony IMX675 camera sensor driver");
MODULE_LICENSE("GPL");
