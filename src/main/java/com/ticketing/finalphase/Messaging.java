package com.ticketing.finalphase;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;
@Configuration
public class Messaging {
    @Bean NewTopic reservationsTopic() { return TopicBuilder.name("ticket-reservations").partitions(3).replicas(1).build(); }
    @Bean NewTopic deadLetterTopic() { return TopicBuilder.name("ticket-reservations.DLT").partitions(3).replicas(1).build(); }
    @Bean org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Object,Object> deadLetterFactory(
            org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            org.springframework.kafka.core.ConsumerFactory<Object,Object> consumerFactory) {
        var factory=new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Object,Object>();
        configurer.configure(factory,consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L,FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
    @Bean DefaultErrorHandler errorHandler(KafkaTemplate<String,String> kafka) {
        var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,e)->new TopicPartition("ticket-reservations.DLT",record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        return new DefaultErrorHandler(recoverer,new FixedBackOff(1000L,2L));
    }
}
