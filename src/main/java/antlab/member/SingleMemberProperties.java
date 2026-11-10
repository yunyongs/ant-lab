package antlab.member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 고정 회원 설정(인증 전 임시). 이메일은 RFC 2606 예약 도메인이라 실제 주소가 아니다.
 * enabled 가 꺼지면 회원을 알 수 없어 시즌 API 가 503 이다(운영 문서 값).
 */
@Validated
@ConfigurationProperties("antlab.single-member")
public record SingleMemberProperties(
        boolean enabled,
        @NotBlank @Email String email,
        @NotBlank @Size(max = 50) String name) {
}
