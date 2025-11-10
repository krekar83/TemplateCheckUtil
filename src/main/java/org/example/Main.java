package org.example;

import com.skax.aiplatform.common.util.TemplateCheckUtil;
import org.apache.tika.Tika;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException {
        // 테스트 파일 목록
        final String filepath = "/Users/krekar83/workspace/doc_samples/";
        final String[] filenames = {
                "o_csv_small_euckr.csv",
                "o_csv_small_utf8.csv",
                "o_excel_small_utf8.xlsx",
                "o_excel_large_utf8.xlsx", // 2GB!!!
                "x_csv_small.csv",
                "x_excel_small_utf8.xlsx"
        };

        System.out.println("=".repeat(80));
        System.out.println("파일 검증 테스트 시작");
        System.out.println("=".repeat(80));
        System.out.println();

        for (int i = 0; i < filenames.length; i++) {
            long startTime = System.nanoTime();
            String filename = filenames[i];
            int fileNumber = i + 1;
            
            System.out.println("-".repeat(80));
            System.out.printf("[%d/%d] 파일 검증 시작: %s%n", fileNumber, filenames.length, filename);
            System.out.println("-".repeat(80));

            try {
                // 테스트 파일 세팅
                File file = new File(filepath + filename);
                if (!file.exists()) {
                    System.out.printf("⚠️  파일을 찾을 수 없습니다: %s%n", file.getAbsolutePath());
                    System.out.println();
                    continue;
                }

                java.nio.file.Path filePath = file.toPath();
                long fileSize = Files.size(filePath);
                String mimeType = Files.probeContentType(filePath);
                if (mimeType == null) {
                    mimeType = new Tika().detect(file);
                }

                System.out.printf("📁 파일 정보:%n");
                System.out.printf("   - 파일명: %s%n", file.getName());
                System.out.printf("   - 파일 크기: %,d bytes (%.2f MB)%n", 
                    fileSize, fileSize / (1024.0 * 1024.0));
                System.out.printf("   - MIME 타입: %s%n", mimeType);
                System.out.println();

                // !!! CSV / EXCEL 파일 검증 부분 (실제 Controller 에 적용해야 할 코드 샘플)
                // 스트리밍 방식의 MultipartFile 생성 (메모리 효율적, 큰 파일도 처리 가능)
                // 주의: 큰 파일(>100MB)의 경우 StreamingMultipartFile을 사용해야 합니다.
                // MockMultipartFile은 파일 전체를 메모리에 로드하므로 OOM이 발생할 수 있습니다.
                MultipartFile multipartFile = new StreamingMultipartFile(file, mimeType);
                
                long validationStartTime = System.nanoTime();
                TemplateCheckUtil.FileCheckResult result = TemplateCheckUtil.validate(multipartFile);
                long validationEndTime = System.nanoTime();
                double validationTimeMs = (validationEndTime - validationStartTime) / 1_000_000.0;

                // 검증 결과 출력
                System.out.printf("🔍 검증 결과:%n");
                Class<?> recordClass = result.getClass();
                if (recordClass.isRecord()) {
                    RecordComponent[] components = recordClass.getRecordComponents();
                    for (RecordComponent component : components) {
                        try {
                            var value = component.getAccessor().invoke(result);
                            String fieldName = component.getName();
                            String displayValue = formatValue(fieldName, value);
                            System.out.printf("   - %s: %s%n", fieldName, displayValue);
                        } catch (Exception e) {
                            System.out.printf("   - %s: <값 조회 실패>%n", component.getName());
                        }
                    }
                }
                System.out.println();

                // 검증 상태 및 실행 시간
                long endTime = System.nanoTime();
                double totalTimeMs = (endTime - startTime) / 1_000_000.0;
                
                if (result.ok()) {
                    System.out.printf("✅ 검증 성공! (검증 시간: %.2f ms, 전체 시간: %.2f ms)%n", 
                        validationTimeMs, totalTimeMs);
                } else {
                    System.out.printf("❌ 검증 실패: %s (검증 시간: %.2f ms, 전체 시간: %.2f ms)%n", 
                        result.message(), validationTimeMs, totalTimeMs);
                }
                
            } catch (Exception e) {
                long endTime = System.nanoTime();
                double totalTimeMs = (endTime - startTime) / 1_000_000.0;
                System.out.printf("❌ 오류 발생: %s (실행 시간: %.2f ms)%n", e.getMessage(), totalTimeMs);
                e.printStackTrace();
            }
            
            System.out.println();
        }
        
        System.out.println("=".repeat(80));
        System.out.println("파일 검증 테스트 완료");
        System.out.println("=".repeat(80));
    }
    
    private static String formatValue(String fieldName, Object value) {
        if (value == null) {
            return "<null>";
        }
        
        if ("ok".equals(fieldName)) {
            return (Boolean) value ? "✓" : "✗";
        }
        
        if (value instanceof Boolean) {
            return value.toString();
        }
        
        return value.toString();
    }
    
    /**
     * 스트리밍 방식의 MultipartFile 구현체.
     * 파일을 메모리에 로드하지 않고 스트리밍으로 처리한다.
     */
    private static class StreamingMultipartFile implements MultipartFile {
        private final File file;
        private final String contentType;
        
        public StreamingMultipartFile(File file, String contentType) {
            this.file = file;
            this.contentType = contentType;
        }
        
        @Override
        public String getName() {
            return "file";
        }
        
        @Override
        public String getOriginalFilename() {
            return file.getName();
        }
        
        @Override
        public String getContentType() {
            return contentType;
        }
        
        @Override
        public boolean isEmpty() {
            return file.length() == 0;
        }
        
        @Override
        public long getSize() {
            return file.length();
        }
        
        @Override
        public byte[] getBytes() throws IOException {
            // 큰 파일의 경우 메모리 문제 방지를 위해 예외 발생
            // TemplateCheckUtil은 getInputStream()만 사용하므로 이 메서드는 호출되지 않음
            long fileSize = file.length();
            if (fileSize > 100 * 1024 * 1024) { // 100MB 이상
                throw new IOException(
                    String.format("큰 파일(%d bytes)은 getBytes()로 읽을 수 없습니다. getInputStream()을 사용하세요.", fileSize)
                );
            }
            return Files.readAllBytes(file.toPath());
        }
        
        @Override
        public InputStream getInputStream() throws IOException {
            // 스트리밍 방식으로 파일을 읽음 (메모리 효율적)
            return new FileInputStream(file);
        }
        
        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            Files.copy(file.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
