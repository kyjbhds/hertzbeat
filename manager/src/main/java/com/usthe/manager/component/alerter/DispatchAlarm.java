package com.usthe.manager.component.alerter;

import com.usthe.alert.AlerterDataQueue;
import com.usthe.alert.AlerterWorkerPool;
import com.usthe.common.entity.alerter.Alert;
import com.usthe.alert.service.AlertService;
import com.usthe.common.util.CommonConstants;
import com.usthe.common.entity.manager.Monitor;
import com.usthe.common.entity.manager.NoticeReceiver;
import com.usthe.manager.service.MailService;
import com.usthe.manager.service.MonitorService;
import com.usthe.manager.service.NoticeConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.List;

/**
 * 告警信息入库分发
 * @author tom
 * @date 2021/12/10 12:58
 */
@Component
@Slf4j
public class DispatchAlarm {

    private AlerterWorkerPool workerPool;
    private AlerterDataQueue dataQueue;
    private AlertService alertService;
    private MonitorService monitorService;
    private NoticeConfigService noticeConfigService;
    private JavaMailSender javaMailSender;
    private RestTemplate restTemplate;
    private MailService mailService;

    public DispatchAlarm(AlerterWorkerPool workerPool, AlerterDataQueue dataQueue,
                         JavaMailSender javaMailSender, NoticeConfigService noticeConfigService,
                         AlertService alertService, MonitorService monitorService, RestTemplate restTemplate, MailService mailService) {
        this.workerPool = workerPool;
        this.dataQueue = dataQueue;
        this.alertService = alertService;
        this.monitorService = monitorService;
        this.noticeConfigService = noticeConfigService;
        this.javaMailSender = javaMailSender;
        this.restTemplate = restTemplate;
        this.mailService = mailService;
        startDispatch();
    }

    private void startDispatch() {
        Runnable runnable = () -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Alert alert = dataQueue.pollAlertData();
                    if (alert != null) {
                        // 判断告警类型入库
                        storeAlertData(alert);
                        // 通知分发
                        sendAlertDataListener(alert);
                    }
                } catch (InterruptedException e) {
                    log.error(e.getMessage());
                }
            }
        };
        workerPool.executeJob(runnable);
        workerPool.executeJob(runnable);
        workerPool.executeJob(runnable);
    }

    private void storeAlertData(Alert alert) {
        // todo 使用缓存不直接操作库
        Monitor monitor = monitorService.getMonitor(alert.getMonitorId());
        if (monitor == null) {
            log.warn("Dispatch alarm the monitorId: {} not existed, ignored.", alert.getMonitorId());
            return;
        }
        alert.setMonitorName(monitor.getName());
        if (monitor.getStatus() == CommonConstants.UN_MANAGE_CODE) {
            // 当监控未管理时  忽略静默其告警信息
            return;
        }
        if (monitor.getStatus() == CommonConstants.AVAILABLE_CODE) {
            if (CommonConstants.AVAILABLE.equals(alert.getTarget())) {
                // 可用性告警 需变更监控状态为不可用
                monitorService.updateMonitorStatus(monitor.getId(), CommonConstants.UN_AVAILABLE_CODE);
            } else if (CommonConstants.REACHABLE.equals(alert.getTarget())) {
                // 可达性告警 需变更监控状态为不可达
                monitorService.updateMonitorStatus(monitor.getId(), CommonConstants.UN_REACHABLE_CODE);
            }
        } else {
            // 若是恢复告警 需对监控状态进行恢复
           if (alert.getStatus() == CommonConstants.ALERT_STATUS_CODE_RESTORED) {
               monitorService.updateMonitorStatus(alert.getMonitorId(), CommonConstants.AVAILABLE_CODE);
           }
        }
        // 告警落库
        alertService.addAlert(alert);
    }

    private void sendAlertDataListener(Alert alert) {
        // todo 转发配置的邮件 微信 webhook
        List<NoticeReceiver> receivers = matchReceiverByNoticeRules(alert);
        // todo 发送通知这里暂时单线程
        for (NoticeReceiver receiver : receivers) {
            switch (receiver.getType()) {
                // todo 短信通知
                case 0: break;
                case 1: sendEmailAlert(receiver, alert); break;
                case 2: sendWebHookAlert(receiver, alert); break;
                case 3: sendWeChatAlert(receiver, alert); break;
                default: break;
            }
        }
    }

    private void sendWeChatAlert(NoticeReceiver receiver, Alert alert) {

    }

    private void sendWebHookAlert(NoticeReceiver receiver, Alert alert) {
        try {
            ResponseEntity<String> entity = restTemplate.postForEntity(receiver.getHookUrl(), alert, String.class);
            if (entity.getStatusCode().value() < HttpStatus.BAD_REQUEST.value()) {
                log.debug("Send WebHook: {} Success", receiver.getHookUrl());
            } else {
                log.warn("Send WebHook: {} Failed", receiver.getHookUrl());
            }
        } catch (ResourceAccessException e) {
            log.warn("Send WebHook: {} Failed: {}.", receiver.getHookUrl(), e.getMessage());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }


    private void sendEmailAlert(final NoticeReceiver receiver,final Alert alert){
        try{
            MimeMessage mimeMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage,true,"UTF-8");
            messageHelper.setSubject("TanCloud探云-监控告警");
            //设置发件人Email
            messageHelper.setFrom("gongchao@tancloud.cn");
            //设定收件人Email
            messageHelper.setTo(receiver.getEmail());        
            messageHelper.setSentDate(new Date());
            //构建邮件模版
            String process = mailService.buildHTMLTemplate(alert);
            //设置邮件内容模版
            messageHelper.setText(process,true);   
            javaMailSender.send(mimeMessage);
        }catch (Exception e){
            log.error("[邮箱告警] error，Exception information={}",e.getMessage());
        }
    }

    private List<NoticeReceiver> matchReceiverByNoticeRules(Alert alert) {
        // todo 使用缓存
        return noticeConfigService.getReceiverFilterRule(alert);
    }


}