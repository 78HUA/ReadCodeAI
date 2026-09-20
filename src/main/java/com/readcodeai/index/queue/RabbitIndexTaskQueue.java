package com.readcodeai.index.queue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.index.store.IndexJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;

/**
 * RabbitMQ 队列：任务**持久化 + 重启续跑 + 消费者可扩**（相对进程内队列买到的东西）。
 *
 * <h3>三条不变量</h3>
 * <ol>
 *   <li><b>不丢</b>：队列持久化 + 消息持久化 + 手动 ack 之前进程死掉 → 消息回到队列被重投。
 *       这正是"重启后自动接着跑"的来源，也是这一版相对进程内队列的**全部价值**。</li>
 *   <li><b>不无限重试</b>：消费失败的异常若是业务错误，抛
 *       {@link AmqpRejectAndDontRequeueException} → 消息进死信队列（DLQ），
 *       而不是在队列里无限打转把 CPU 烧光。</li>
 *   <li><b>不过度设计</b>：这里**不引 Kafka / 不用手动 ack / 不做消息去重表** ——
 *       任务是分钟级的长活、每分钟几条的量级；at-least-once + 执行端幂等就够了
 *       （幂等见 {@link IndexTaskRunner}）。换更重的组件换不来任何可测量的提升。</li>
 * </ol>
 *
 * <p>消费者容器**由本类持有并显式启停**（不用 {@code @RabbitListener} 注解）：
 * 这样"暂停消费/恢复消费"是可测的（重启续跑的实验要靠它），装配也只在一个地方发生。
 */
public class RabbitIndexTaskQueue implements IndexTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(RabbitIndexTaskQueue.class);

    private final RabbitTemplate template;
    private final AmqpAdmin admin;
    private final IndexTaskRunner runner;
    private final IndexJobRepository jobs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String queueName;
    private final String dlqName;
    private final SimpleMessageListenerContainer container;

    public RabbitIndexTaskQueue(RabbitTemplate template, AmqpAdmin admin, ConnectionFactory connectionFactory,
                                IndexTaskRunner runner, IndexJobRepository jobs,
                                ReadCodeAiProperties.Queue props) {
        this.template = template;
        this.admin = admin;
        this.runner = runner;
        this.jobs = jobs;
        this.queueName = props.getName();
        this.dlqName = props.getDlqName();

        // 死信用默认交换机转发：x-dead-letter-routing-key 指向 DLQ 名字即可（不用额外建交换机）
        Queue main = QueueBuilder.durable(queueName)
                .deadLetterExchange("")
                .deadLetterRoutingKey(dlqName)
                .build();
        Queue dead = QueueBuilder.durable(dlqName).build();
        admin.declareQueue(main);
        admin.declareQueue(dead);

        this.container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setConcurrentConsumers(props.getConcurrency());
        container.setMaxConcurrentConsumers(props.getConcurrency());
        container.setPrefetchCount(1);          // 一次只给一个任务：索引是长活，多预取没意义
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setMessageListener(this::onMessage);
        container.setDefaultRequeueRejected(false);   // 未被显式拒绝的异常也不打转，送死信
        container.start();
        log.info("RabbitMQ 索引队列已启用：{}（消费者 {} 个 · 死信 {}）",
                queueName, props.getConcurrency(), dlqName);
    }

    @Override
    public void enqueue(IndexTask task) {
        // 显式声明持久化：队列持久 + 消息持久，重启后才谈得上"不丢"
        template.convertAndSend(queueName, serialize(task), message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });
        log.info("已投递索引任务：{}（队列 {}）", task.describe(), queueName);
    }

    @Override
    public int queued() {
        try {
            QueueInformation info = admin.getQueueInfo(queueName);
            return info == null ? 0 : (int) Math.min(Integer.MAX_VALUE, info.getMessageCount());
        } catch (RuntimeException e) {
            // broker 查询失败不该影响主流程（界面上显示 0 就是"看不到"，不是"没有任务"）
            log.debug("查询队列深度失败：{}", e.toString());
            return 0;
        }
    }

    @Override
    public int running() {
        // 正在跑的任务数以**任务表**为准（跨实例也准；broker 那边只看得见"未被 ack 的条数"）
        Integer running = jobs.countByStatus("RUNNING");
        return running == null ? 0 : running;
    }

    @Override
    public String describe() {
        return "RabbitMQ（队列 " + queueName + " · 消费者 " + container.getActiveConsumerCount()
                + " · 重启后自动续跑）";
    }

    /** 死信队列里积了多少条 —— 出问题时要能一眼看见（界面/巡检/测试都用它）。 */
    public int deadLettered() {
        QueueInformation info = admin.getQueueInfo(dlqName);
        return info == null ? 0 : (int) Math.min(Integer.MAX_VALUE, info.getMessageCount());
    }

    public String queueName() {
        return queueName;
    }

    public String dlqName() {
        return dlqName;
    }

    /** 暂停/恢复消费：重启续跑的实验靠它模拟"消费者不在"，运维时也能用来让 worker 停下来。 */
    public void pauseConsuming() {
        container.stop();
        log.info("已暂停消费（队列 {} 里的消息会留着）", queueName);
    }

    public void resumeConsuming() {
        container.start();
        log.info("已恢复消费（队列 {}）", queueName);
    }

    /**
     * 收一条消息并执行。
     *
     * <p>业务失败 → {@link AmqpRejectAndDontRequeueException} → 消息进 DLQ。
     * **不给重试**：索引失败的常见原因是代码解析不了 / 文件读不了，重试还是同一个结果；
     * 真需要重试的是"临时性故障"（比如数据库连不上），那种情况现在的做法是让人重新提交，
     * 而不是让消息在队列里无声地转圈。
     */
    private void onMessage(Message message) {
        IndexTask task;
        try {
            task = deserialize(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // 连消息都读不懂：只能进死信，留着原文方便排查
            log.error("索引任务消息无法解析，送死信队列：{}", e.toString());
            throw new AmqpRejectAndDontRequeueException("消息格式错误", e);
        }
        try {
            runner.run(task);
        } catch (RuntimeException | LinkageError e) {
            log.warn("任务执行失败，消息送死信队列（{}）：{}", dlqName, task.describe());
            throw new AmqpRejectAndDontRequeueException("索引失败：" + e.getMessage(), e);
        }
    }

    String serialize(IndexTask task) {
        return mapper.writeValueAsString(new Envelope(task.jobId(), task.type().name(), task.payload()));
    }

    IndexTask deserialize(String json) {
        Envelope envelope = mapper.readValue(json, Envelope.class);
        if (envelope.type() == null || envelope.payload() == null) {
            throw new IllegalArgumentException("消息缺字段：type/payload 必填");
        }
        return new IndexTask(envelope.jobId(), IndexTask.Type.valueOf(envelope.type()), envelope.payload());
    }

    /** 消息体：**故意是最小的三个字段**，避免把随版本变化的内部结构写进 MQ。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(long jobId, String type, String payload) {
    }
}
