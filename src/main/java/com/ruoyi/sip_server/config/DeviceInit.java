package com.ruoyi.sip_server.config;

import cn.hutool.core.thread.ThreadUtil;
import com.ruoyi.config.FileConfig;
import com.ruoyi.domain.Device;
import com.ruoyi.domain.DeviceChannel;
import com.ruoyi.domain.ZLMediaKit;
import com.ruoyi.media.config.ZLMediaKitConfig;
import com.ruoyi.subscribe.EventPublisher;
import com.ruoyi.utils.SipCmdUtil;
import com.ruoyi.utils.SystemUtils;
import com.ruoyi.utils.ZLMediaKitHttpUtil;
import com.ruoyi.web.controller.gb28181.GB28181Controller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@EnableScheduling // 开启定时任务
public class DeviceInit {

    @Autowired
    private SipCmdUtil sipCmdUtil;
    @Autowired
    private SipConfig sipConfig;
    @Autowired
    private ZLMediaKitConfig zlm;
    @Autowired
    private ZLMediaKitHttpUtil httpUtil;

    @Autowired
    private GB28181Controller controller;

    @Autowired
    private ZLMediaKitConfig zlMediaKitConfig;

    @Autowired
    private TaskExecutor my;

    /**
     * 所有设备
     */
    public static Map<String, Device> ds;
    public static boolean isRegister = true;
    private static Process ffmpegProcess;


    @PostConstruct
    public void construct() {
        log.info("\n=========设备初始化=========");
        ds = new ConcurrentHashMap<>(sipConfig.getDeviceSize());
        // 关闭所有流
        if (zlMediaKitConfig.isReset()) {
            httpUtil.closeStreams(null, null, null, null, null, "1");
        }
        String[] split = zlMediaKitConfig.getRtpProxyPortRange().split("-");
        int portBegin = Integer.parseInt(split[0]);
        // 创建设备
        for (int i = 0; i < sipConfig.getDeviceSize(); i++) {
            Device d = new Device();
            d.setDeviceId(new BigInteger(sipConfig.getGbCodeBegin()).add(BigInteger.valueOf(i)).toString());
            d.setDeviceName((i + 1) + "-设备");
            d.setDeviceIp(sipConfig.getSipDeviceIp());
            d.setDevicePort(sipConfig.getSipDevicePort());
            // 设置流媒体推流ip端口
            d.setZlmIp(zlm.getHttpIp());
            d.setZlmPort(portBegin++);
            d.setCharset("GB2312");
            d.setRegisterProtocol("UDP");
            d.setStreamProtocol("UDP");

            DeviceChannel c = new DeviceChannel();
            c.setParentId(d.getDeviceId());
            c.setChannelId(d.getDeviceId());
            c.setChannelName((i + 1) + "-通道");
            d.setChannel(c);
            ds.put(d.getDeviceId(), d);
        }


        ZLMediaKit mediaKit = zlm.getDefaultZLMediaKit();
        if ("1".equals(mediaKit.getEnabled())) {
            String ffmpegCommand = mediaKit.getFfmpegCommand();
            if (StringUtils.hasText(ffmpegCommand)) {
                System.err.println("等待五秒钟,等待流媒体重启完毕,开始拉流");
                ThreadUtil.sleep(5000);
                ffmpegPush(ffmpegCommand);
            } else {
                while (true) {
                    boolean b = httpUtil.playPullStream(null);
                    if (!b) {
                        log.error("流媒体拉流异常，5秒后重试...");
                        ThreadUtil.sleep(5000);
                    } else {
                        System.err.println("流媒体拉基础流成功");
                        break;
                    }
                }
            }
        }

        // 注册
        my.execute(()->{
            controller.cmd(0);
        });
    }

    /**
     * 推流
     *
     * @param ffmpegCommand ffmege 推流指令
     */
    public static void ffmpegPush(String ffmpegCommand) {

        SystemUtils.SystemType type = SystemUtils.getSystem();
        if (type.equals(SystemUtils.SystemType.Win)) {
            ffmpegCommand = ffmpegCommand.replaceFirst("ffmpeg", FileConfig.win.replace("\\", "/"));
            // ffmpegCommand = ffmpegCommand.replaceFirst("ffmpeg", FileConfig.win);
        } else {
            ffmpegCommand = ffmpegCommand.replaceFirst("ffmpeg", FileConfig.linux.replace("\\", "/"));
        }

        ffmpegCommand = ffmpegCommand.replaceFirst("input.mp4", FileConfig.mp4File.replace("\\", "/"));

        String finalFfmpegCommand = ffmpegCommand;


        new Thread(() -> {
            while (true) {
                try {
                    System.err.println("开始推流");
                    System.err.println("ffmpeg 推流命令: " + finalFfmpegCommand);
                    // 创建ProcessBuilder对象，并传入FFmpeg命令
                    ProcessBuilder processBuilder = new ProcessBuilder(finalFfmpegCommand.split(" "));

                    // 设置输出流和错误流合并
                    processBuilder.redirectErrorStream(true);

                    // 启动进程
                    Process process = processBuilder.start();
                    ffmpegProcess = process;

                    // 读取FFmpeg输出
                    InputStream inputStream = process.getInputStream();
                    Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
                    String output = scanner.hasNext() ? scanner.next() : "";

                    // 等待进程执行完成
                    // int exitCode = process.waitFor();

                    // 打印FFmpeg输出和退出码
                    System.out.println("FFmpeg output:\n" + output);
                    // System.out.println("Exit code: " + exitCode);
                    if (output.contains("Unknown error") || output.contains("Conversion failed!")) {
                        log.warn("推流失败，等待5秒钟后重试...");
                        ThreadUtil.sleep(5000);
                        continue;
                    }
                    System.err.println("推流结束");
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

    }

    @PreDestroy
    public void destroy() {
        log.info("\n=========设备销毁=========");
        // 关闭所有流
        if (zlMediaKitConfig.isReset()) {
            httpUtil.closeStreams(null, null, null, null, null, "1");
        }
        // 关闭ffmpeg推流
        if (ffmpegProcess != null) {
            log.info("关闭ffmpeg推流");
            ffmpegProcess.destroy();
        }
    }


    // 定时的方法
    // @Scheduled(cron = "* */3 * * * *") // 每24小时执行一次
   /* @Async("my") // 设置为异步执行 不设置则默认为单线程,不管多少个方法,都会一个个按照顺序执行,一个线程睡,所有方法停
    public void condition() {
        if (isRegister){
            ds.values().stream().filter(x->!x.isRegister()).forEach(x->eventPublisher.eventPush(new SipRegisterEvent(x)));
        }else {
            ds.values().stream().filter(x->!x.isRegister()).forEach(x->eventPublisher.eventPush(new SipRegisterEvent(x,true)));
        }
    }*/

}
