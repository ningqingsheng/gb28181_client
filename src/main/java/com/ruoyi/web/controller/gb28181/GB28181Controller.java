package com.ruoyi.web.controller.gb28181;

import cn.hutool.core.thread.ThreadUtil;
import com.ruoyi.delayed_task.DelayQueueManager;
import com.ruoyi.delayed_task.DelayTask;
import com.ruoyi.domain.Device;
import com.ruoyi.domain.base.Prefix;
import com.ruoyi.domain.base.R;
import com.ruoyi.media.config.ZLMediaKitConfig;
import com.ruoyi.sip_server.config.DeviceInit;
import com.ruoyi.sip_server.config.SSRCConfig;
import com.ruoyi.sip_server.config.SipConfig;
import com.ruoyi.subscribe.EventPublisher;
import com.ruoyi.subscribe.event.SipRegisterEvent;
import com.ruoyi.utils.SipCmdUtil;
import com.ruoyi.utils.ZLMediaKitHttpUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟国标
 */
@Slf4j
@RestController
@RequestMapping("gb-client")
@Api(tags = "模拟国标")
public class GB28181Controller {

    @Autowired
    private SipCmdUtil sipCmdUtil;

    @Autowired
    private ZLMediaKitConfig kitConfig;
    @Autowired
    private ZLMediaKitHttpUtil kitHttpUtil;

    @Autowired
    private SSRCConfig ssrcConfig;

    @Autowired
    private EventPublisher eventPublisher;
    @Autowired
    private SipConfig sipConfig;
    @Autowired
    private DelayQueueManager delayQueueManager;

    /**
     * 设备注册/注销
     */
    @ApiOperation("0注册/1注销")
    @PostMapping("cmd")
    public synchronized R<String> cmd(int type) {
        if (type == 0) {
            // 注册
            DeviceInit.ds.values().forEach(x -> {
                        register(x);
                        ThreadUtil.sleep(sipConfig.getInitRegisterInterval());
                    }
            );
            DeviceInit.isRegister = true;
            return R.success("注册中");
        } else {
            // 注销
            DeviceInit.ds.values().forEach(x -> eventPublisher.eventPush(new SipRegisterEvent(x, true)));
            DeviceInit.isRegister = false;
            return R.success("注销中");
        }

    }

    private void register(Device x) {
        log.info("{}开始注册", x.getDeviceId());
        eventPublisher.eventPush(new SipRegisterEvent(x));
        if (!delayQueueManager.isExistence(Prefix.register, x.getDeviceId())) {
            // 自动重新注册
            delayQueueManager.put(new DelayTask(Prefix.register, x.getDeviceId(), sipConfig.getRegisterInterval() * 1000, () -> register(x)));
        }
    }

}
