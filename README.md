# TemplateCheckUtil

CSV 및 Excel 파일의 포맷과 인코딩을 검증하는 Java 유틸리티 라이브러리입니다.

## 주요 기능

- **CSV 파일 검증**: UTF-8/UTF-8-SIG 인코딩 검증 및 포맷 확인
- **Excel 파일 검증**: XLS/XLSX 포맷 검증 (대용량 파일 지원)
- **메모리 효율적 처리**: 스트리밍 방식으로 큰 파일도 안전하게 처리
- **MIME 타입 자동 감지**: Apache Tika를 활용한 정확한 파일 타입 감지
- **인코딩 감지**: ICU4J를 활용한 정확한 문자 인코딩 감지

## 요구사항

- Java 17 이상
- Maven 3.6 이상

## 의존성

- Spring Boot 3.5.4
- Apache POI 5.4.1
- Apache Tika 2.9.4
- ICU4J 58.1

## 사용 방법

### MultipartFile로 검증

```java
import com.skax.aiplatform.common.util.TemplateCheckUtil;
import org.springframework.web.multipart.MultipartFile;

MultipartFile file = ...; // 업로드된 파일
TemplateCheckUtil.FileCheckResult result = TemplateCheckUtil.validate(file);

if (result.ok()) {
    System.out.println("검증 성공: " + result.fileType());
    System.out.println("인코딩: " + result.encoding());
} else {
    System.out.println("검증 실패: " + result.message());
}
```

### Path로 직접 검증

```java
import java.nio.file.Path;
import com.skax.aiplatform.common.util.TemplateCheckUtil;

Path filePath = Paths.get("example.xlsx");
TemplateCheckUtil.FileCheckResult result = TemplateCheckUtil.validate(filePath, "example.xlsx");

if (result.ok()) {
    System.out.println("검증 성공!");
}
```

## 검증 결과

`FileCheckResult` 레코드에는 다음 정보가 포함됩니다:

- `ok`: 검증 성공 여부 (boolean)
- `message`: 검증 메시지 (String)
- `mimeType`: 감지된 MIME 타입 (String)
- `fileType`: 파일 타입 (CSV 또는 EXCEL)
- `encoding`: 인코딩 (CSV인 경우 UTF-8 또는 UTF-8-SIG)

## 대용량 파일 지원

이 유틸리티는 스트리밍 방식을 사용하여 대용량 파일(수 GB)도 메모리 효율적으로 처리할 수 있습니다.

## 예제

프로젝트의 `Main.java` 파일을 참조하세요.

## 라이센스

이 프로젝트는 Apache License 2.0을 따릅니다.

