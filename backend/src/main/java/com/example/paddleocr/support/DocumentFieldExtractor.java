package com.example.paddleocr.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DocumentFieldExtractor {

    private static final Pattern ID_CARD_NO = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}[.-]\\d{1,2}[.-]\\d{1,2}\\b");
    private static final Pattern PASSPORT_NO = Pattern.compile("\\b[EeGgPpKkDdSs]\\d{7,8}\\b");
    private static final Pattern LICENSE_NO = Pattern.compile("\\b\\d{12,18}\\b");

    public String detectDocumentType(String text) {
        String normalized = text == null ? "" : text.replace(" ", "");
        if (normalized.contains("中华人民共和国居民身份证") || normalized.contains("公民身份号码")) {
            return "ID_CARD";
        }
        if (normalized.contains("中华人民共和国机动车驾驶证") || normalized.contains("驾驶证")) {
            return "DRIVING_LICENSE";
        }
        if (normalized.contains("营业执照") || normalized.contains("统一社会信用代码")) {
            return "BUSINESS_LICENSE";
        }
        if (normalized.contains("PASSPORT") || normalized.contains("中华人民共和国护照") || normalized.contains("护照")) {
            return "PASSPORT";
        }
        return "GENERAL_DOCUMENT";
    }

    public Map<String, String> extract(String documentType, String text) {
        return switch (documentType) {
            case "ID_CARD" -> extractIdCard(text);
            case "DRIVING_LICENSE" -> extractDrivingLicense(text);
            case "BUSINESS_LICENSE" -> extractBusinessLicense(text);
            case "PASSPORT" -> extractPassport(text);
            default -> extractGeneric(text);
        };
    }

    private Map<String, String> extractIdCard(String text) {
        Map<String, String> fields = extractGeneric(text);
        fields.put("documentTypeLabel", "居民身份证");
        match(ID_CARD_NO, text).ifPresent(value -> fields.put("idCardNumber", value));
        fields.put("name", findValueAfter(text, "姓名"));
        fields.put("gender", findValueAfter(text, "性别"));
        fields.put("ethnicity", findValueAfter(text, "民族"));
        fields.put("address", findValueAfter(text, "住址"));
        fields.put("birthDate", match(DATE, text).orElse(""));
        return fields;
    }

    private Map<String, String> extractDrivingLicense(String text) {
        Map<String, String> fields = extractGeneric(text);
        fields.put("documentTypeLabel", "机动车驾驶证");
        fields.put("name", findValueAfter(text, "姓名"));
        fields.put("licenseNo", findValueAfter(text, "证号"));
        if (fields.get("licenseNo").isBlank()) {
            match(LICENSE_NO, text).ifPresent(value -> fields.put("licenseNo", value));
        }
        fields.put("address", findValueAfter(text, "住址"));
        fields.put("vehicleType", findValueAfter(text, "准驾车型"));
        fields.put("validFrom", findNthDate(text, 0));
        fields.put("validTo", findNthDate(text, 1));
        return fields;
    }

    private Map<String, String> extractBusinessLicense(String text) {
        Map<String, String> fields = extractGeneric(text);
        fields.put("documentTypeLabel", "营业执照");
        fields.put("companyName", findValueAfter(text, "名称"));
        fields.put("creditCode", findValueAfter(text, "统一社会信用代码"));
        fields.put("legalRepresentative", findValueAfter(text, "法定代表人"));
        fields.put("address", findValueAfter(text, "住所"));
        fields.put("registeredCapital", findValueAfter(text, "注册资本"));
        fields.put("establishDate", findNthDate(text, 0));
        return fields;
    }

    private Map<String, String> extractPassport(String text) {
        Map<String, String> fields = extractGeneric(text);
        fields.put("documentTypeLabel", "护照");
        fields.put("passportNo", match(PASSPORT_NO, text).orElse(""));
        fields.put("surname", findValueAfter(text, "Surname"));
        fields.put("givenNames", findValueAfter(text, "Given names"));
        fields.put("nationality", findValueAfter(text, "Nationality"));
        fields.put("birthDate", findNthDate(text, 0));
        fields.put("expiryDate", findNthDate(text, 1));
        return fields;
    }

    private Map<String, String> extractGeneric(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        String normalized = text == null ? "" : text.trim();
        fields.put("summary", normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized);
        fields.put("lineCount", String.valueOf(normalized.isBlank() ? 0 : normalized.split("\\R+").length));
        return fields;
    }

    private Optional<String> match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group());
        }
        return Optional.empty();
    }

    private String findNthDate(String text, int index) {
        Matcher matcher = DATE.matcher(text == null ? "" : text);
        int current = 0;
        while (matcher.find()) {
            if (current == index) {
                return matcher.group();
            }
            current++;
        }
        return "";
    }

    private String findValueAfter(String text, String label) {
        if (text == null || label == null) {
            return "";
        }
        Pattern pattern = Pattern.compile(Pattern.quote(label) + "[:：]?\\s*([^\\r\\n]{1,40})");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }
}
