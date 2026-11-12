package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 응답 record 의 구성 요소가 SQL 정의 {@code sr_report} 의 {@code RETURNS TABLE} 37열과 이름·순서가 같다(snake → camel). 표본·DB 가 필요 없다. */
class SeasonReportColumnContractTest {

    @Test
    void 응답_구성_요소는_정의의_출력_열과_이름과_순서가_같다() throws IOException {
        List<String> components = Arrays.stream(SeasonReportDto.class.getRecordComponents()).map(RecordComponent::getName).toList();
        List<String> columns = SeasonReportColumns.sqlColumns().stream().map(SeasonReportColumns::camel).toList();
        assertThat(columns).hasSize(37);
        assertThat(components).containsExactlyElementsOf(columns);
    }
}
