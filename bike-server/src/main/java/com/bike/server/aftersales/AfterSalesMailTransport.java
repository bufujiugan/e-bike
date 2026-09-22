package com.bike.server.aftersales;

import jakarta.mail.internet.InternetAddress;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
public class AfterSalesMailTransport {
    private final AfterSalesMailConfig config;
    public AfterSalesMailTransport(AfterSalesMailConfig config){this.config=config;}
    static String address(String value) {
        if(value==null||value.isBlank()||value.length()>254||value.chars().anyMatch(Character::isISOControl))return null;
        try {
            var addresses=InternetAddress.parse(value.trim(),true);
            if(addresses.length!=1||addresses[0].isGroup())return null;
            addresses[0].validate();String email=addresses[0].getAddress();
            return email.contains("@")?email:null;
        }catch(jakarta.mail.internet.AddressException e){return null;}
    }
    boolean configured(){return config.getHost()!=null&&!config.getHost().isBlank()&&config.getPort()>0&&config.getPort()<=65535&&address(config.getFrom())!=null;}
    public void send(AfterSalesNotification notification,String recipient) throws Exception {
        var sender=new JavaMailSenderImpl();sender.setHost(config.getHost());sender.setPort(config.getPort());
        sender.setUsername(config.getUsername());sender.setPassword(config.getPassword());sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        var properties=sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth",Boolean.toString(config.getUsername()!=null&&!config.getUsername().isBlank()));
        properties.setProperty("mail.smtp.starttls.enable",Boolean.toString(config.getStartTls()));
        properties.setProperty("mail.smtp.starttls.required",Boolean.toString(config.getStartTls()));
        properties.setProperty("mail.smtp.ssl.enable",Boolean.toString(config.getSsl()));
        properties.setProperty("mail.smtp.ssl.checkserveridentity","true");
        properties.setProperty("mail.smtp.connectiontimeout","10000");properties.setProperty("mail.smtp.timeout","10000");properties.setProperty("mail.smtp.writetimeout","10000");
        var message=sender.createMimeMessage();var helper=new MimeMessageHelper(message,false,StandardCharsets.UTF_8.name());
        helper.setFrom(address(config.getFrom()));helper.setTo(recipient);helper.setSubject(notification.subject());helper.setText(notification.body(),false);
        message.setHeader("Message-ID","<after-sales-"+notification.messageKey()+"@ebike.local>");
        sender.send(message);
    }
}
