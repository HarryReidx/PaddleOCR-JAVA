package com.example.paddleocr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.paddleocr.entity.OcrNotificationEntity;
import com.example.paddleocr.mapper.OcrNotificationMapper;
import com.example.paddleocr.model.OcrNotificationResponse;
import com.example.paddleocr.service.OcrNotificationService;
import com.example.paddleocr.support.JsonSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class OcrNotificationServiceImpl implements OcrNotificationService {

    private final OcrNotificationMapper notificationMapper;
    private final JsonSupport jsonSupport;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public OcrNotificationServiceImpl(OcrNotificationMapper notificationMapper, JsonSupport jsonSupport) {
        this.notificationMapper = notificationMapper;
        this.jsonSupport = jsonSupport;
    }

    @Override
    public void publish(String taskNo, String eventType, String message, Map<String, Object> payload) {
        OcrNotificationEntity entity = new OcrNotificationEntity();
        entity.setTaskNo(taskNo);
        entity.setEventType(eventType);
        entity.setMessage(message);
        entity.setPayloadJson(jsonSupport.toJson(payload));
        entity.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(entity);

        OcrNotificationResponse response = toResponse(entity);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(response));
            } catch (Exception ex) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
                emitters.remove(emitter);
            }
        }
    }

    @Override
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError((ex) -> emitters.remove(emitter));
        return emitter;
    }

    @Override
    public List<OcrNotificationResponse> listRecent(int limit) {
        return notificationMapper.selectList(new LambdaQueryWrapper<OcrNotificationEntity>()
                .orderByDesc(OcrNotificationEntity::getId)
                .last("limit " + Math.max(1, Math.min(limit, 100))))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    private OcrNotificationResponse toResponse(OcrNotificationEntity entity) {
        OcrNotificationResponse response = new OcrNotificationResponse();
        response.setTaskNo(entity.getTaskNo());
        response.setEventType(entity.getEventType());
        response.setMessage(entity.getMessage());
        response.setPayload(jsonSupport.readObjectMap(entity.getPayloadJson()));
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}