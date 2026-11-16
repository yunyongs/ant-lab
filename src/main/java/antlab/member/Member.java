package antlab.member;

import antlab.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 — 가변 엔티티. 비밀번호는 해시만 담는다(해싱은 인증 기능이 한다). 이메일 유일성은 DB 의 lower(email) 인덱스가 지킨다.
 * An agent member ({@link MemberKind#AGENT}) has neither e-mail nor password.
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "email", length = 255, nullable = true)
    private String email;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "password", length = 255, nullable = true)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 5, nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 5, nullable = false)
    private MemberKind kind;

    public Member(String email, String name, String password) {
        this.email = email;
        this.name = name;
        this.password = password;
        this.role = MemberRole.USER;
        this.kind = MemberKind.HUMAN;
    }

    private Member(String name) {
        this.name = name;
        this.role = MemberRole.USER;
        this.kind = MemberKind.AGENT;
    }

    /** An agent member has no e-mail and no password (ck_member_kind_credentials) and always the USER role. */
    public static Member agent(String name) {
        return new Member(name);
    }
}
