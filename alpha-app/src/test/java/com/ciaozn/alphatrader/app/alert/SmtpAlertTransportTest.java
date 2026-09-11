package com.ciaozn.alphatrader.app.alert;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mail that would leave (T401, FR-OP-03), inspected on this side of the wire: the assertions are
 * made on the {@code MimeMessage} before it is handed to {@code send}, which {@code CapturingMailSender}
 * intercepts. Nothing here opens a socket - JavaMail only connects when the message is actually sent.
 *
 * <p>The addresses are the interesting part. A mail with a missing {@code From} is refused by the relay
 * with an error that reads like a network fault, and one whose body lost its charset turns every Chinese
 * rule name into question marks; both failures happen at the worst possible moment and are invisible in
 * unit tests unless something asserts exactly this.
 */
class SmtpAlertTransportTest {

    /** Captures instead of sending: same construction path, no relay. */
    static final class CapturingMailSender extends JavaMailSenderImpl {

        MimeMessage captured;

        CapturingMailSender() {
            setDefaultEncoding("UTF-8");
            setJavaMailProperties(new Properties());
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            this.captured = mimeMessage;
        }
    }

    @Test
    void theMailIsAddressedFromAndToWhatThePropertiesSay() throws Exception {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpAlertTransport transport =
                new SmtpAlertTransport(sender, "ciaozn@qq.com", "ops@example.com");

        transport.send("[alpha][CRITICAL] EX-reconcile-position-mismatch", "detail: book says 0.5, ws drifts");

        MimeMessage mail = sender.captured;
        assertThat(mail.getFrom()[0].toString()).isEqualTo("ciaozn@qq.com");
        assertThat(mail.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("ops@example.com");
        assertThat(mail.getSubject()).isEqualTo("[alpha][CRITICAL] EX-reconcile-position-mismatch");
    }

    @Test
    void theBodyKeepsItsChineseAndIsLabelledUtf8() throws Exception {
        CapturingMailSender sender = new CapturingMailSender();
        SmtpAlertTransport transport = new SmtpAlertTransport(sender, "from@example.com", "to@example.com");

        transport.send("[alpha][CRITICAL] RK-05-breaker", "detail: 当日亏损超过 5%，停止开仓");

        MimeMessage mail = sender.captured;
        // JavaMail stamps Content-Type: charset=UTF-8 when the message is written out; the real send
        // does that inside JavaMailSender.send, which this double intercepted, so it is done here to
        // observe the bytes that would have gone out rather than the ones that are still in memory.
        mail.saveChanges();
        assertThat(mail.getContentType()).contains("charset=UTF-8");
        assertThat((String) mail.getContent()).contains("当日亏损超过 5%");
    }

    @Test
    void aMailNobodyCanAnswerIsRefusedAtConstructionRatherThanAt3am() {
        CapturingMailSender sender = new CapturingMailSender();

        assertThatThrownBy(() -> new SmtpAlertTransport(sender, "  ", "to@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
        assertThatThrownBy(() -> new SmtpAlertTransport(sender, "from@example.com", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to");
    }
}
