package io.scalecube.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.membership.MembershipEvent;
import io.scalecube.cluster.membership.MembershipEvent.Type;
import io.scalecube.transport.Address;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ClusterTest extends BaseTest {

  public static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Test
  public void testMembersAccessFromScheduler() {
    // Start seed node
    Cluster seedNode = Cluster.joinAwait();
    Cluster otherNode = Cluster.joinAwait(seedNode.address());

    assertEquals(2, seedNode.members().size());
    assertEquals(2, otherNode.members().size());

    // Members by address

    Optional<Member> otherNodeOnSeedNode = seedNode.member(otherNode.address());
    Optional<Member> seedNodeOnOtherNode = otherNode.member(seedNode.address());

    assertEquals(otherNode.member(), otherNodeOnSeedNode.orElse(null));
    assertEquals(seedNode.member(), seedNodeOnOtherNode.orElse(null));
  }

  @Test
  public void testJoinLocalhostIgnored() {
    // Start seed node
    Cluster seedNode =
        Cluster.joinAwait(
            ClusterConfig.builder()
                .port(4801)
                .connectTimeout(500)
                .seedMembers(Address.from("localhost:4801"), Address.from("127.0.0.1:4801"))
                .build());

    Collection<Member> otherMembers = seedNode.otherMembers();
    assertEquals(0, otherMembers.size(), "otherMembers: " + otherMembers);
  }

  @Test
  public void testJoinLocalhostIgnoredWithOverride() {
    // Start seed node
    Cluster seedNode =
        Cluster.joinAwait(
            ClusterConfig.builder()
                .port(7878)
                .memberHost("localhost")
                .memberPort(7878)
                .connectTimeout(500)
                .seedMembers(Address.from("localhost:7878"))
                .build());

    Collection<Member> otherMembers = seedNode.otherMembers();
    assertEquals(0, otherMembers.size(), "otherMembers: " + otherMembers);
  }

  @Test
  public void testJoinDynamicPort() {
    // Start seed node
    Cluster seedNode = Cluster.joinAwait();

    int membersNum = 10;
    List<Cluster> otherNodes = new ArrayList<>(membersNum);
    try {
      // Start other nodes
      long startAt = System.currentTimeMillis();
      for (int i = 0; i < membersNum; i++) {
        otherNodes.add(Cluster.joinAwait(seedNode.address()));
      }
      LOGGER.info("Start up time: {} ms", System.currentTimeMillis() - startAt);
      assertEquals(membersNum + 1, seedNode.members().size());
      LOGGER.info("Cluster nodes: {}", seedNode.members());
    } finally {
      // Shutdown all nodes
      shutdown(
          Stream.concat(
                  Stream.of(seedNode), //
                  otherNodes.stream())
              .collect(Collectors.toList()));
    }
  }

  @Test
  public void testUpdateMetadata() throws Exception {
    // Start seed member
    Cluster seedNode = Cluster.joinAwait();

    Cluster metadataNode = null;
    int testMembersNum = 10;
    List<Cluster> otherNodes = new ArrayList<>(testMembersNum);
    try {
      // Start member with metadata
      Map<String, String> metadata = new HashMap<>();
      metadata.put("key1", "value1");
      metadata.put("key2", "value2");
      metadataNode = Cluster.joinAwait(metadata, seedNode.address());

      // Start other test members
      Flux.range(0, testMembersNum)
          .flatMap(integer -> Cluster.join(seedNode.address()))
          .doOnNext(otherNodes::add)
          .blockLast(TIMEOUT);

      TimeUnit.SECONDS.sleep(3);

      // Check all test members know valid metadata
      for (Cluster node : otherNodes) {
        Optional<Member> memberOptional = node.member(metadataNode.member().id());
        assertTrue(memberOptional.isPresent());
        Member member = memberOptional.get();
        assertEquals(metadata, node.metadata(member));
      }

      // Subscribe for membership update event all nodes
      CountDownLatch updateLatch = new CountDownLatch(testMembersNum);
      for (Cluster node : otherNodes) {
        node.listenMembership()
            .filter(MembershipEvent::isUpdated)
            .subscribe(
                event -> {
                  LOGGER.info("Received membership update event: {}", event);
                  updateLatch.countDown();
                });
      }

      // Update metadata
      Map<String, String> updatedMetadata = Collections.singletonMap("key1", "value3");
      metadataNode.updateMetadata(updatedMetadata).block(TIMEOUT);

      // Check all nodes had updated metadata member
      for (Cluster node : otherNodes) {
        Optional<Member> memberOptional = node.member(metadataNode.member().id());
        assertTrue(memberOptional.isPresent());
        Member member = memberOptional.get();
        assertEquals(updatedMetadata, node.metadata(member));
      }
    } finally {
      // Shutdown all nodes
      shutdown(
          Stream.concat(
                  Stream.of(seedNode, metadataNode), //
                  otherNodes.stream())
              .collect(Collectors.toList()));
    }
  }

  @Test
  public void testUpdateMetadataProperty() throws Exception {
    // Start seed member
    Cluster seedNode = Cluster.joinAwait();

    Cluster metadataNode = null;
    int testMembersNum = 10;
    List<Cluster> otherNodes = new ArrayList<>(testMembersNum);

    try {
      // Start member with metadata
      Map<String, String> metadata = new HashMap<>();
      metadata.put("key1", "value1");
      metadata.put("key2", "value2");
      metadataNode = Cluster.joinAwait(metadata, seedNode.address());

      // Start other test members
      Flux.range(0, testMembersNum)
          .flatMap(integer -> Cluster.join(seedNode.address()))
          .doOnNext(otherNodes::add)
          .blockLast(TIMEOUT);

      TimeUnit.SECONDS.sleep(3);

      // Check all test members know valid metadata
      for (Cluster node : otherNodes) {
        Optional<Member> memberOptional = node.member(metadataNode.member().id());
        assertTrue(memberOptional.isPresent());
        Member member = memberOptional.get();
        assertEquals(metadata, node.metadata(member));
      }

      // Subscribe for membership update event all nodes
      CountDownLatch updateLatch = new CountDownLatch(testMembersNum);
      for (Cluster node : otherNodes) {
        node.listenMembership()
            .filter(MembershipEvent::isUpdated)
            .subscribe(
                event -> {
                  LOGGER.info("Received membership update event: {}", event);
                  updateLatch.countDown();
                });
      }

      // Update metadata
      metadataNode.updateMetadataProperty("key2", "value3").block(TIMEOUT);

      // Check all nodes had updated metadata member
      for (Cluster node : otherNodes) {
        Optional<Member> memberOptional = node.member(metadataNode.member().id());
        assertTrue(memberOptional.isPresent());
        Member member = memberOptional.get();
        Map<String, String> actualMetadata = node.metadata(member);
        assertEquals(2, actualMetadata.size());
        assertEquals("value1", actualMetadata.get("key1"));
        assertEquals("value3", actualMetadata.get("key2"));
      }
    } finally {
      // Shutdown all nodes
      shutdown(
          Stream.concat(
                  Stream.of(seedNode, metadataNode), //
                  otherNodes.stream())
              .collect(Collectors.toList()));
    }
  }

  @Test
  public void testRemoveMetadataProperty() throws Exception {
    // Start seed member
    Cluster seedNode = Cluster.joinAwait();

    Cluster metadataNode = null;
    int testMembersNum = 10;
    List<Cluster> otherNodes = new ArrayList<>(testMembersNum);

    try {
      // Start member with metadata
      Map<String, String> metadata = new HashMap<>();
      metadata.put("key1", "value1");
      metadata.put("key2", "value2");
      metadataNode = Cluster.joinAwait(metadata, seedNode.address());

      // Start other test members
      Flux.range(0, testMembersNum)
          .flatMap(integer -> Cluster.join(seedNode.address()))
          .doOnNext(otherNodes::add)
          .blockLast(TIMEOUT);

      TimeUnit.SECONDS.sleep(3);

      // Check all test members know valid metadata
      for (Cluster node : otherNodes) {
        Optional<Member> memberOptional = node.member(metadataNode.member().id());
        assertTrue(memberOptional.isPresent());
        Member member = memberOptional.get();
        assertEquals(metadata, node.metadata(member));
      }

      // Subscribe for membership update event all nodes
      CountDownLatch updateLatch = new CountDownLatch(testMembersNum);
      for (Cluster node : otherNodes) {
        node.listenMembership()
            .filter(MembershipEvent::isUpdated)
            .subscribe(
                event -> {
                  LOGGER.info("Received membership update event: {}", event);
                  updateLatch.countDown();
                });
      }

      // Update metadata
      metadataNode.removeMetadataProperty("key2").block(TIMEOUT);

      // Check all nodes had updated metadata member
      for (Cluster node : otherNodes) {
        Optional<Member> memberOptional = node.member(metadataNode.member().id());
        assertTrue(memberOptional.isPresent());
        Member member = memberOptional.get();
        Map<String, String> actualMetadata = node.metadata(member);
        assertEquals(1, actualMetadata.size());
        assertEquals("value1", actualMetadata.get("key1"));
        assertNull(actualMetadata.get("key2"));
      }
    } finally {
      // Shutdown all nodes
      shutdown(
          Stream.concat(
                  Stream.of(seedNode, metadataNode), //
                  otherNodes.stream())
              .collect(Collectors.toList()));
    }
  }

  @Test
  public void testShutdownCluster() throws Exception {
    // Start seed member
    final Cluster seedNode = Cluster.joinAwait();

    // Start nodes
    final Cluster node1 = Cluster.joinAwait(seedNode.address());
    final Cluster node2 = Cluster.joinAwait(seedNode.address());
    final Cluster node3 = Cluster.joinAwait(seedNode.address());

    CountDownLatch latch = new CountDownLatch(3);

    Flux.merge(seedNode.listenMembership(), node1.listenMembership(), node3.listenMembership())
        .filter(MembershipEvent::isRemoved)
        .filter(event -> event.member().id().equals(node2.member().id()))
        .doOnNext(event -> latch.countDown())
        .subscribe();

    node2.shutdown().block(TIMEOUT);

    assertTrue(latch.await(TIMEOUT.getSeconds(), TimeUnit.SECONDS));
    assertTrue(node2.isShutdown());

    shutdown(Stream.of(seedNode, node1, node3).collect(Collectors.toList()));
  }

  @Test
  public void testMemberMetadataRemoved() throws InterruptedException {
    // Start seed member
    Map<String, String> seedMetadata = new HashMap<>();
    seedMetadata.put("seed", "shmid");
    final Cluster seedNode = Cluster.joinAwait(seedMetadata);

    // Start nodes
    // Start member with metadata
    Map<String, String> node1Metadata = new HashMap<>();
    node1Metadata.put("node", "shmod");
    final Cluster node1 = Cluster.joinAwait(node1Metadata, seedNode.address());

    // Check events
    MembershipEvent nodeAddedEvent =
        seedNode.listenMembership().as(Mono::from).block(Duration.ofSeconds(3));
    assertEquals(Type.ADDED, nodeAddedEvent.type());

    MembershipEvent seedAddedEvent =
        node1.listenMembership().as(Mono::from).block(Duration.ofSeconds(3));
    assertEquals(Type.ADDED, seedAddedEvent.type());

    // Check metadata
    assertEquals(node1Metadata, seedNode.metadata(node1.member()));
    assertEquals(seedMetadata, node1.metadata(seedNode.member()));

    // Remove node1 from cluster
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Map<String, String>> removedMetadata = new AtomicReference<>();
    seedNode
        .listenMembership()
        .filter(MembershipEvent::isRemoved)
        .subscribe(
            event -> {
              removedMetadata.set(event.oldMetadata());
              latch.countDown();
            });

    node1.shutdown().subscribe();

    assertTrue(latch.await(TIMEOUT.getSeconds(), TimeUnit.SECONDS));
    assertEquals(removedMetadata.get(), node1Metadata);
  }

  @Test
  public void testJoinSeedClusterWithNoExistingSeedMember() {
    // Start seed node
    Cluster seedNode = Cluster.joinAwait();

    Address nonExistingSeed1 = Address.from("localhost:1234");
    Address nonExistingSeed2 = Address.from("localhost:5678");
    Address[] seeds = new Address[] {nonExistingSeed1, nonExistingSeed2, seedNode.address()};

    Cluster otherNode = Cluster.joinAwait(seeds);

    assertEquals(otherNode.member(), seedNode.otherMembers().iterator().next());
    assertEquals(seedNode.member(), otherNode.otherMembers().iterator().next());
  }

  private void shutdown(List<Cluster> nodes) {
    try {
      Mono.when(
              nodes
                  .stream() //
                  .map(Cluster::shutdown)
                  .collect(Collectors.toList()))
          .block(TIMEOUT);
    } catch (Exception ex) {
      LOGGER.error("Exception on cluster shutdown", ex);
    }
  }
}
