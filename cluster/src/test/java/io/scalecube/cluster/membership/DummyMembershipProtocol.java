package io.scalecube.cluster.membership;

import io.scalecube.cluster.Member;
import io.scalecube.cluster.transport.Address;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DummyMembershipProtocol implements MembershipProtocol {

  private Member localMember;
  private List<Member> remoteMembers = new ArrayList<>();

  public DummyMembershipProtocol(Address localAddress, List<Address> allAddresses) {
    int count = 0;
    for (Address address : allAddresses) {
      Member member = new Member(Integer.toString(count++), address);
      if (address.equals(localAddress)) {
        localMember = member;
      } else {
        remoteMembers.add(member);
      }
    }
  }

  @Override
  public Member member() {
    return localMember;
  }

  @Override
  public void updateMetadata(Map<String, String> metadata) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void updateMetadataProperty(String key, String value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Flux<MembershipEvent> listen() {
    return Flux.fromStream(remoteMembers.stream()
        .map(MembershipEvent::createAdded));
  }
}
