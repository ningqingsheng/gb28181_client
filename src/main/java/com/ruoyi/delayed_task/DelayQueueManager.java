package com.ruoyi.delayed_task;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.json.JSONUtil;
import com.ruoyi.domain.base.Prefix;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DelayQueueManager implements CommandLineRunner {
    // 任务队列
    private final DelayQueue<DelayTask> delayQueue = new DelayQueue<>();
    // 正在队列里的对象
    private final Map<String, DelayTask> saveDelay = new ConcurrentHashMap<>();
    @Autowired
    private AsyncTaskExecutor my;

    /**
     * 加入到延时队列中
     *
     * @param task 任务
     */
    public void put(DelayTask task) {
        saveDelay.put(task.getId(), task);
        delayQueue.put(task);
        // log.info("\n延时任务进入队列 [{}]", task.getId());
    }

    /**
     * 是否存在
     *
     * @param id 标识
     */
    public boolean isExistence(Prefix prefix, String id) {
        String prefixId = prefix + id;
        return saveDelay.containsKey(prefixId);
    }

    /**
     * 取消延时任务
     *
     * @param id 标识
     * @return
     */
    public boolean remove(Prefix prefix, String id) {
        String prefixId = prefix + id;
        DelayTask task = saveDelay.get(prefixId);
        if (task != null) {
            saveDelay.remove(prefixId);
            boolean b = delayQueue.remove(task);
            log.info("\n移除延时队列任务 [{}]", task.getId());
            return b;
        }
        return false;
    }


    /**
     * 初始化延时队列
     *
     * @param args
     * @throws Exception
     */
    @Override
    public void run(String... args) {
        Executors.newSingleThreadExecutor().execute(new Thread(this::executeThread));
    }

    /**
     * 延时任务执行线程
     */
    @SneakyThrows
    private void executeThread() {
        while (true) {
            DelayTask task = delayQueue.take();
            my.execute(() -> {
                try {
                    //执行任务
                    task.getExecute().execute();
                    log.info("正在处理{}，当前活动线程数量{}，delayQueue={}", task.getId(), ((ThreadPoolTaskExecutor) my).getActiveCount(), delayQueue.size());
//                    log.info("正在处理{}，当前活动线程数量{}，delayQueueSize={}，delayQueue=\n{}", task.getId(), ((ThreadPoolTaskExecutor) my).getActiveCount(), delayQueue.size(),
//                            JSONUtil.toJsonPrettyStr(delayQueue.stream().map(delayTask -> delayTask.getId()+ "    " + LocalDateTimeUtil.of(delayTask.getExpire())).collect(Collectors.toList()))
//                    );
                } catch (Exception e) {
                    log.error("延时任务执行出错", e);
                } finally {
                    // 删除执行过的任务
                    saveDelay.remove(task.getId());
                }
            });
        }
    }

}
