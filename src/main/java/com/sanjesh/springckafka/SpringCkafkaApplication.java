package com.sanjesh.springckafka;

import com.github.javafaker.Faker;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

@SpringBootApplication
@EnableKafkaStreams
public class SpringCkafkaApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpringCkafkaApplication.class, args);
  }

  @Bean
  NewTopic hobbit2() {
    return TopicBuilder.name("hobbit2").partitions(8).replicas(5).build();
  }

  @Bean
  NewTopic wordCount() {
    return TopicBuilder.name("streams-wordcount-output")
        .partitions(2)
        .replicas(3)
        .build();
  }

}

@RequiredArgsConstructor
@Component
class Producer {

  private final KafkaTemplate<Integer, String> template;

  private final KafkaProperties properties;
  Faker faker;

  @EventListener(ApplicationStartedEvent.class)
  public void generate() {

    properties.setBootstrapServers(new ArrayList<>(Collections.singletonList(
        "pkc-l6wr6.europe-west2.gcp.confluent.cloud:9092")));
    faker = Faker.instance();

    final Flux<Long> interval = Flux.interval(Duration.ofMillis(1_1000));
    final Flux<String> quote = Flux.fromStream(Stream.generate(() -> faker.hobbit().quote()));

    Flux.zip(interval, quote).map((Function<Tuple2<Long, String>, Object>) it -> template.send(
        "hobbit", faker.random().nextInt(42), it.getT2())).blockLast();
  }
}

@RequiredArgsConstructor
@Component
class Consumer {


  @KafkaListener(topics = {"streams-wordcount-output"}, groupId = "spring-boot-kafka")
  public void consume(ConsumerRecord<String, Long> quote) {
    System.out.println("received: key:" + quote.key() + " value: " + quote.value());
  }
}


@RequiredArgsConstructor
@Component
class Processor {

  @Autowired
  public void process(StreamsBuilder builder) {

    final Serde<Integer> integerSerde = Serdes.Integer();
    final Serde<String> stringSerde = Serdes.String();
    final Serde<Long> longSerde = Serdes.Long();

    //KStream<Integer, String> textLines = builder.stream("hobbit", Consumed.with(integerSerde, stringSerde));

    KStream<Integer, String> textLines = builder.stream("hobbit",
        Consumed.with(integerSerde, stringSerde));

    KTable<String, Long> wordCounts = textLines
        .flatMapValues(value -> Arrays.asList(value.toLowerCase().split("\\W+")))
        .groupBy((key, value) -> value, Grouped.with(stringSerde, stringSerde))
        .count(Materialized.as("counts"));
    //wordCounts.toStream().to("streams-wordcount-output", Produced.with(stringSerde, longSerde));
    wordCounts.toStream().to("streams-wordcount-output", Produced.with(stringSerde, longSerde));
  }
}


@RequiredArgsConstructor
@RestController
class RestService {

  private final StreamsBuilderFactoryBean factoryBean;

  @GetMapping("/count/{word}")
  public Long getCount(@PathVariable String word){

    System.out.println("In get count for word: " + word);
    final KafkaStreams kafkaStreams = factoryBean.getKafkaStreams();
    final ReadOnlyKeyValueStore<String, Long> counts = kafkaStreams.store(
        StoreQueryParameters.fromNameAndType("counts", QueryableStoreTypes.keyValueStore()));
    return counts.get(word);
  }
}

