package antlab.member;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.persistence.UniqueViolations;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupService {

    private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_member_email";

    private final MemberRepository members;
    private final PasswordEncoder encoder;
    private final EntityManager entityManager;

    public SignupService(MemberRepository members, PasswordEncoder encoder, EntityManager entityManager) {
        this.members = members;
        this.encoder = encoder;
        this.entityManager = entityManager;
    }

    @Transactional
    public MemberDto signup(SignupRequest request) {
        // The pre-check keeps the common duplicate from aborting the transaction; the unique index decides races.
        if (members.findByEmail(request.email()).isPresent()) {
            throw emailTaken();
        }
        Member member = new Member(request.email(), request.name(), encoder.encode(request.password()));
        try {
            members.save(member);
            // Flush inside the try so a unique violation surfaces here, not at commit.
            entityManager.flush();
        } catch (DataIntegrityViolationException e) {
            if (UniqueViolations.isViolationOf(e, EMAIL_UNIQUE_CONSTRAINT)) {
                throw emailTaken();
            }
            throw e;
        }
        return new MemberDto(member.getId(), member.getName());
    }

    private static DomainException emailTaken() {
        return new DomainException(ErrorCode.EMAIL_TAKEN, "이미 가입한 이메일이다");
    }
}
