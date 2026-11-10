package antlab.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 고정 회원 설정: 켜짐/꺼짐 구현 선택, 운영 가드, 설정 검증(기동 실패). 스프링 전체 기동 없이 문맥만 띄운다. */
class SingleMemberResolverTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SingleMemberProperties.class)
    static class Base {
        @Bean
        MemberRepository memberRepository() {
            return mock(MemberRepository.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Base.class, SingleMemberResolver.class, UnavailableCurrentMember.class,
                    SingleMemberProductionGuard.class)
            .withPropertyValues("antlab.single-member.email=player@example.invalid", "antlab.single-member.name=감독");

    @Test
    void 켜면_고정_회원_구현이_뜬다() {
        runner.withPropertyValues("antlab.single-member.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(CurrentMember.class)).isInstanceOf(SingleMemberResolver.class);
        });
    }

    @Test
    void 꺼지면_시즌_API가_쓰는_두_메서드가_모두_MEMBER_UNAVAILABLE이다() {
        runner.withPropertyValues("antlab.single-member.enabled=false").run(ctx -> {
            CurrentMember member = ctx.getBean(CurrentMember.class);
            assertThat(member).isInstanceOf(UnavailableCurrentMember.class);
            assertThatThrownBy(member::existingId).isInstanceOfSatisfying(DomainException.class,
                    e -> assertThat(e.getCode()).isEqualTo(ErrorCode.MEMBER_UNAVAILABLE));
            assertThatThrownBy(member::ensureId).isInstanceOfSatisfying(DomainException.class,
                    e -> assertThat(e.getCode()).isEqualTo(ErrorCode.MEMBER_UNAVAILABLE));
        });
    }

    @Test
    void 운영_프로필에서_켜져_있으면_기동에_실패한다() {
        runner.withPropertyValues("spring.profiles.active=prod", "antlab.single-member.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void 운영_프로필에서_꺼져_있으면_기동한다() {
        runner.withPropertyValues("spring.profiles.active=prod", "antlab.single-member.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 이메일이_형식이_아니거나_이름이_비면_기동에_실패한다() {
        runner.withPropertyValues("antlab.single-member.enabled=true", "antlab.single-member.email=not-an-email")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("antlab.single-member.enabled=true", "antlab.single-member.name= ")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("antlab.single-member.enabled=true", "antlab.single-member.name=" + "가".repeat(51))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void 이미_있는_회원은_잠가서_읽고_만들지_않는다() {
        MemberRepository members = mock(MemberRepository.class);
        Member existing = new Member("player@example.invalid", "감독", "!");
        when(members.findByEmailForUpdate("player@example.invalid")).thenReturn(Optional.of(existing));
        SingleMemberResolver resolver = new SingleMemberResolver(members,
                new SingleMemberProperties(true, "player@example.invalid", "감독"));
        // 번호는 DB 가 주므로 단위 시험에서는 null 이다 — 잠금 조회를 썼고 저장하지 않았음을 본다
        assertThatThrownBy(resolver::ensureId).isInstanceOf(NullPointerException.class);
        verify(members).findByEmailForUpdate("player@example.invalid");
        org.mockito.Mockito.verify(members, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void 없으면_로그인_불가_표식_비밀번호로_만든다() {
        MemberRepository members = mock(MemberRepository.class);
        when(members.findByEmailForUpdate("player@example.invalid")).thenReturn(Optional.empty());
        when(members.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SingleMemberResolver resolver = new SingleMemberResolver(members,
                new SingleMemberProperties(true, "player@example.invalid", "감독"));
        assertThatThrownBy(resolver::ensureId).isInstanceOf(NullPointerException.class); // 번호 null(DB 미사용)
        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo("!");
        assertThat(saved.getValue().getEmail()).isEqualTo("player@example.invalid");
    }
}
