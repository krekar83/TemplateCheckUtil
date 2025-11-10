package com.skax.aiplatform.common.util;

import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 업로드된 템플릿 파일(csv / excel)의 포맷과 인코딩을 검증하는 유틸리티.
 */
public final class TemplateCheckUtil {
    private static final int CSV_SNIFF_BYTES = 4096;
    private static final int CHARSET_SAMPLE_BYTES = 1_000_000;
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final String TEMP_FILE_PREFIX = "upload-";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    // 파일 확장자
    private static final String EXT_XLSX = ".xlsx";
    private static final String EXT_XLS = ".xls";
    private static final String EXT_CSV = ".csv";
    
    // MIME 타입
    private static final String MIME_XLS = "application/vnd.ms-excel";
    private static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_TEXT_CSV = "text/csv";
    private static final String MIME_TEXT_PLAIN = "text/plain";
    private static final String MIME_TIKA_OOXML = "application/x-tika-ooxml";
    private static final String MIME_WORD = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String MIME_PRESENTATION = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    
    // 인코딩
    private static final String ENC_UTF8 = "UTF-8";
    private static final String ENC_UTF8_SIG = "UTF-8-SIG";
    
    // 성공 메시지
    private static final String MSG_SUCCESS_CSV = "검증 성공(CSV, UTF-8)";
    private static final String MSG_SUCCESS_EXCEL = "검증 성공(Excel)";
    
    // 에러 메시지
    private static final String ERR_EMPTY_FILE = "빈 파일입니다.";
    private static final String ERR_UNSUPPORTED_TYPE = "CSV 또는 Excel(xls/xlsx)만 허용됩니다. (감지된 MIME: ";
    private static final String ERR_FILE_PROCESS = "파일 처리 오류: ";
    private static final String ERR_CSV_ENCODING_UNKNOWN = "CSV 인코딩을 판별할 수 없습니다.";
    private static final String ERR_CSV_ENCODING_INVALID = "CSV는 UTF-8 이어야 합니다. (감지: ";
    private static final String ERR_CSV_EMPTY = "CSV 내용이 비어 있습니다.";
    private static final String ERR_XLSX_INVALID = "XLSX 포맷 오류: ";
    private static final String ERR_XLSX_NO_SHEET = "XLSX 시트를 찾을 수 없습니다.";
    private static final String ERR_XLS_INVALID = "XLS 포맷 오류: ";
    private static final String ERR_EXCEL_INVALID = "Excel 포맷 오류: ";
    
    // OOXML 관련
    private static final String OOXML_CONTENT_TYPES_XML = "[Content_Types].xml";
    private static final String OOXML_SPREADSHEET_MAIN = "spreadsheetml.sheet.main+xml";
    private static final String OOXML_WORD_MAIN = "wordprocessingml.document.main+xml";
    private static final String OOXML_PRESENTATION_MAIN = "presentationml.presentation.main+xml";
    private static final String OOXML_WORKBOOK = "Workbook";

    private TemplateCheckUtil() {
    }

    /**
     * 지원 파일 포맷 유형.
     */
    public enum FileType {
        CSV,
        EXCEL
    }

    /**
     * 검증 결과.
     */
    public record FileCheckResult(boolean ok, String message, String mimeType, FileType fileType, String encoding) { }

    /**
     * 업로드된 멀티파트 파일이 CSV/Excel 템플릿 요건을 만족하는지 검증한다.
     */
    public static FileCheckResult validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return fail(ERR_EMPTY_FILE);
        }

        Path temp = null;
        try {
            temp = copyToTemp(file);
            String originalName = file.getOriginalFilename();
            return validatePath(temp, originalName, false);
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignore) {
                    // swallow cleanup failure
                }
            }
        }
    }

    /**
     * 파일 경로로 직접 CSV/Excel 템플릿 요건을 만족하는지 검증한다.
     * 큰 파일을 처리할 때 메모리 효율적이다.
     * 
     * @param filePath 검증할 파일의 경로
     * @param originalName 원본 파일명 (확장자 판별을 위해 사용)
     * @param deleteAfterValidation 검증 후 파일을 삭제할지 여부
     * @return 검증 결과
     */
    public static FileCheckResult validate(Path filePath, String originalName, boolean deleteAfterValidation) {
        if (filePath == null || !Files.exists(filePath)) {
            return fail(ERR_EMPTY_FILE);
        }
        
        try {
            return validatePath(filePath, originalName, deleteAfterValidation);
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        } finally {
            if (deleteAfterValidation) {
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException ignore) {
                    // swallow cleanup failure
                }
            }
        }
    }

    /**
     * 파일 경로로 직접 CSV/Excel 템플릿 요건을 만족하는지 검증한다 (파일 삭제 안 함).
     * 
     * @param filePath 검증할 파일의 경로
     * @param originalName 원본 파일명 (확장자 판별을 위해 사용)
     * @return 검증 결과
     */
    public static FileCheckResult validate(Path filePath, String originalName) {
        return validate(filePath, originalName, false);
    }

    /**
     * 내부 검증 로직 (공통).
     */
    private static FileCheckResult validatePath(Path path, String originalName, boolean deleteAfterValidation) throws IOException {
        String mime = detectMime(path, originalName);
        FileType fileType = determineFileType(mime, path, originalName);
        if (fileType == null) {
            return fail(ERR_UNSUPPORTED_TYPE + mime + ")");
        }

        return switch (fileType) {
            case CSV -> validateCsv(path, mime);
            case EXCEL -> validateExcel(path, mime, originalName);
        };
    }

    private static Path copyToTemp(MultipartFile file) throws IOException {
        String suffix = Optional.ofNullable(file.getOriginalFilename())
                .filter(name -> name.contains("."))
                .map(name -> name.substring(name.lastIndexOf('.')))
                .orElse("");
        
        // 시분초밀리세컨드 타임스탬프를 포함한 유니크한 파일명 생성
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String fileName = TEMP_FILE_PREFIX + timestamp + suffix;
        Path temp = Files.createTempFile(fileName, null);
        
        // 스트리밍 방식으로 복사 (메모리 효율적, 큰 파일도 처리 가능)
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    private static String detectMime(Path path, String originalName) throws IOException {
        DefaultDetector detector = new DefaultDetector();
        Metadata metadata = new Metadata();
        if (originalName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
        }
        try (TikaInputStream stream = TikaInputStream.get(path, metadata)) {
            MediaType mediaType = detector.detect(stream, metadata);
            String mime = mediaType.toString();
            if (MIME_TIKA_OOXML.equalsIgnoreCase(mime)) {
                return refineOoxmlMime(path, mime);
            }
            return mime;
        }
    }

    private static FileType determineFileType(String mime, Path path, String originalName) {
        boolean isExcel = isExcelMime(mime);
        boolean isCsv = isCsvMimeOrHeuristic(mime, path, originalName);
        if (!isExcel && !isCsv) {
            return null;
        }
        return isCsv ? FileType.CSV : FileType.EXCEL;
    }

    private static FileCheckResult validateCsv(Path path, String mime) {
        String detected = detectCharset(path, CHARSET_SAMPLE_BYTES);
        String normalized = normalizeUtf8(detected, path);
            if (normalized == null) {
            return fail(ERR_CSV_ENCODING_UNKNOWN);
        }
        if (!normalized.equalsIgnoreCase(ENC_UTF8) && !normalized.equalsIgnoreCase(ENC_UTF8_SIG)) {
            return fail(ERR_CSV_ENCODING_INVALID + detected + ")");
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            if (reader.readLine() == null) {
                return fail(ERR_CSV_EMPTY);
            }
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        }
        return ok(MSG_SUCCESS_CSV, mime, FileType.CSV, normalized);
    }

    private static FileCheckResult validateExcel(Path path, String mime, String originalName) {
        String extension = extractExtension(originalName);
        try {
            if (EXT_XLSX.equalsIgnoreCase(extension)) {
                validateXlsx(path);
            } else if (EXT_XLS.equalsIgnoreCase(extension)) {
                validateXls(path);
            } else {
                validateFallbackWorkbook(path);
            }
            return ok(MSG_SUCCESS_EXCEL, mime, FileType.EXCEL, null);
        } catch (IOException e) {
            return fail(ERR_FILE_PROCESS + e.getMessage());
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
    }

    private static void validateXlsx(Path path) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            var iterator = reader.getSheetsData();
            if (!iterator.hasNext()) {
                throw new IOException(ERR_XLSX_INVALID + ERR_XLSX_NO_SHEET);
            }
            try (InputStream ignored = iterator.next()) {
                // accessing first sheet is enough
            }
        } catch (Exception e) {
            throw new IOException(ERR_XLSX_INVALID + e.getMessage(), e);
        }
    }

    private static void validateXls(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             POIFSFileSystem fileSystem = new POIFSFileSystem(input);
             InputStream workbookStream = fileSystem.createDocumentInputStream(OOXML_WORKBOOK)) {
            HSSFRequest request = new HSSFRequest();
            request.addListenerForAllRecords(event -> {
                // no-op listener: presence of records implies valid structure
            });
            HSSFEventFactory factory = new HSSFEventFactory();
            factory.processEvents(request, workbookStream);
        } catch (Exception e) {
            throw new IOException(ERR_XLS_INVALID + e.getMessage(), e);
        }
    }

    private static void validateFallbackWorkbook(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            // success if workbook opens
        } catch (Exception e) {
            throw new IOException(ERR_EXCEL_INVALID + e.getMessage(), e);
        }
    }

    private static String refineOoxmlMime(Path path, String fallbackMime) {
        try (InputStream input = Files.newInputStream(path);
             ZipInputStream zipStream = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                if (OOXML_CONTENT_TYPES_XML.equals(entry.getName())) {
                    String xml = new String(zipStream.readAllBytes(), StandardCharsets.UTF_8);
                    if (xml.contains(OOXML_SPREADSHEET_MAIN)) {
                        return MIME_XLSX;
                    }
                    if (xml.contains(OOXML_WORD_MAIN)) {
                        return MIME_WORD;
                    }
                    if (xml.contains(OOXML_PRESENTATION_MAIN)) {
                        return MIME_PRESENTATION;
                    }
                    break;
                }
            }
        } catch (IOException ignore) {
            // unable to refine, return fallback
        }
        return fallbackMime;
    }

    private static boolean isExcelMime(String mime) {
        if (mime == null) {
            return false;
        }
        return mime.equalsIgnoreCase(MIME_XLS) || mime.equalsIgnoreCase(MIME_XLSX);
    }

    private static boolean isCsvMimeOrHeuristic(String mime, Path path, String name) {
        boolean byMime = MIME_TEXT_CSV.equalsIgnoreCase(mime)
                || MIME_TEXT_PLAIN.equalsIgnoreCase(mime)
                || MIME_XLS.equalsIgnoreCase(mime);
        boolean byExtension = name != null && name.toLowerCase().endsWith(EXT_CSV);
        boolean byContent = looksLikeCsv(path);
        return (byMime && (byExtension || byContent)) || (byExtension && byContent);
    }

    private static boolean looksLikeCsv(Path path) {
        byte[] head = readHead(path, CSV_SNIFF_BYTES);
        String sample = new String(stripBomIfNecessary(head), StandardCharsets.ISO_8859_1);
        boolean hasDelimiter = sample.contains(",") || sample.contains(";") || sample.contains("\t");
        boolean hasNewLine = sample.contains("\n") || sample.contains("\r");
        return hasDelimiter && hasNewLine;
    }

    private static byte[] readHead(Path path, int size) {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(size);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String extractExtension(String originalName) {
        if (originalName == null) {
            return "";
        }
        int index = originalName.lastIndexOf('.');
        return index >= 0 ? originalName.substring(index) : "";
    }

    private static String detectCharset(Path path, int maxBytes) {
        try {
            // 파일의 처음 부분만 읽어서 인코딩 감지 (효율적)
            byte[] sample = readHead(path, maxBytes);
            if (sample.length == 0) {
                return null;
            }
            
            // ICU4J CharsetDetector 사용 (Tika가 내부적으로 사용하는 라이브러리)
            CharsetDetector detector = new CharsetDetector();
            detector.setText(sample);
            CharsetMatch match = detector.detect();
            
            if (match != null && match.getConfidence() > 0) {
                return match.getName();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeUtf8(String detected, Path path) {
        try {
            byte[] head = readHead(path, 4);
            if (hasUtf8Bom(head)) {
                return ENC_UTF8_SIG;
            }
            if (detected == null) {
                return null;
            }
            if (detected.equalsIgnoreCase(ENC_UTF8) || detected.equalsIgnoreCase(ENC_UTF8_SIG)) {
                return detected.toUpperCase();
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                char[] buffer = new char[COPY_BUFFER_SIZE];
                while (reader.read(buffer) != -1) {
                    // just read to ensure decoding succeeds
                }
            }
            return ENC_UTF8;
        } catch (Exception e) {
            return detected;
        }
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
    }

    private static byte[] stripBomIfNecessary(byte[] bytes) {
        return hasUtf8Bom(bytes) ? Arrays.copyOfRange(bytes, 3, bytes.length) : bytes;
    }

    private static FileCheckResult ok(String message, String mime, FileType type, String encoding) {
        return new FileCheckResult(true, message, mime, type, encoding);
    }

    private static FileCheckResult fail(String message) {
        return new FileCheckResult(false, message, null, null, null);
    }
}
