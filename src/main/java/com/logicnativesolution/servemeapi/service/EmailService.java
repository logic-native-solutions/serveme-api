package com.logicnativesolution.servemeapi.service;

import lombok.AllArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
@AllArgsConstructor
public class EmailService {
    private JavaMailSender mailSender;

    public int generateEmailPhoneOtp() {
        Random random = new Random();
        int randomInt;
        randomInt = 100_000 + random.nextInt(900_000);
        return randomInt;
    }
    public void sendEmail(String to, String subject, String body){
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("mapharitezeey004@gmail.com");
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

}
