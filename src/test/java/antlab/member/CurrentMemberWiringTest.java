package antlab.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import antlab.auth.JwtCurrentMember;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AssignableTypeFilter;

/** The token's member is the only {@link CurrentMember} in every profile; a leftover fixed-member property changes nothing. */
class CurrentMemberWiringTest {

    @Configuration(proxyBeanMethods = false)
    static class Base {
        @Bean
        MemberRepository memberRepository() {
            return mock(MemberRepository.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Base.class, JwtCurrentMember.class);

    private static void assertOnlyJwtMember(org.springframework.context.ApplicationContext ctx) {
        assertThat(ctx.getBeansOfType(CurrentMember.class)).hasSize(1).allSatisfy((name, bean) ->
                assertThat(bean).isInstanceOf(JwtCurrentMember.class));
    }

    @Test
    void 속성이_없어도_토큰의_회원_구현_하나만_뜬다() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertOnlyJwtMember(ctx);
        });
    }

    @Test
    void local_프로필에서도_토큰의_회원_구현_하나만_뜬다() {
        runner.withPropertyValues("spring.profiles.active=local").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertOnlyJwtMember(ctx);
        });
    }

    @Test
    void prod_프로필에서도_토큰의_회원_구현_하나만_뜬다() {
        runner.withPropertyValues("spring.profiles.active=prod").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertOnlyJwtMember(ctx);
        });
    }

    @Test
    void 남아_있는_고정_회원_속성은_아무것도_바꾸지_않는다() {
        runner.withPropertyValues("antlab.single-member.enabled=true", "antlab.single-member.email=player@example.invalid")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertOnlyJwtMember(ctx);
                });
    }

    @Test
    void test_프로필에서도_토큰의_회원_구현_하나만_뜬다() {
        runner.withPropertyValues("spring.profiles.active=test").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertOnlyJwtMember(ctx);
        });
    }

    @Test
    void prod_프로필에_옛_고정_회원_속성이_남아_있어도_하나만_뜬다() {
        runner.withPropertyValues("spring.profiles.active=prod", "antlab.single-member.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertOnlyJwtMember(ctx);
                });
    }

    @Test
    void main_코드에는_현재_회원_구현이_토큰의_회원_하나뿐이다() {
        // Record matches inside the filter: the scanner evaluates @Profile and @Conditional after its filters,
        // which would hide exactly the conditional duplicate this test exists to find.
        AssignableTypeFilter implementsCurrentMember = new AssignableTypeFilter(CurrentMember.class);
        List<String> found = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> {
            // only the main output: test support classes (the direct-call fallback) are not production wiring
            boolean mainOutput = reader.getResource().getURL().getPath().contains("/classes/java/main/");
            if (mainOutput && reader.getClassMetadata().isConcrete() && implementsCurrentMember.match(reader, factory)) {
                found.add(reader.getClassMetadata().getClassName());
            }
            return false;
        });
        scanner.findCandidateComponents("antlab");
        found.sort(null);

        assertThat(found).containsExactly("antlab.auth.JwtCurrentMember");
    }

    @Test
    void 토큰의_회원_구현에는_조건이_붙지_않는다() {
        assertThat(AnnotatedElementUtils.hasMetaAnnotationTypes(JwtCurrentMember.class, Conditional.class)).isFalse();
    }
}
