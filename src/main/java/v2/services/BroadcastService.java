package v2.services;

import org.springframework.stereotype.Service;
import v2.connectors.base.BaseConnector;
import v2.connectors.max.MaxConnector;
import v2.connectors.tg.TgConnector;
import v2.connectors.vk.VkConnector;
import v2.dto.BroadcastRequest;
import v2.dto.BroadcastResponse;
import v2.entity.Message;
import v2.repository.UserRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ИЗМЕНЕНИЯ относительно вашей версии:
 *
 *  1) Защита от null/пустого списка тегов.
 *     Раньше: findUserIdsByTags(null) → NPE, findUserIdsByTags(List.of()) → ошибка JPQL `IN ()`.
 *     Теперь: сразу возвращаем BroadcastResponse(0,0,0) с внятной ошибкой, до похода в БД.
 *
 *  2) Защита от connectorType == null при sendToAllConnectors == false.
 *     Раньше: request.getConnectorType().toLowerCase() → NPE.
 *
 *  3) Получатели выбираются с учётом источника (findUserIdsBySourceAndTags) при рассылке
 *     через один коннектор: userId у VK и у TG — разные пространства, и слать «VK-подписчикам»
 *     через TG бессмысленно. Для sendToAllConnectors берём всех (как и было).
 *     ⚠️ Если у вас задумано иначе (теги общие на все платформы) — верните findUserIdsByTags,
 *        это одна строка, помечена ниже.
 *
 *  4) resolvePeerId больше не «заглушка, возвращающая что дали»: userId из таблицы users —
 *     это уже id в соцсети, то есть peer_id для VK. Для TG/MAX логика может отличаться —
 *     место помечено.
 *
 *  5) Ошибки в allErrors дополнены текстом, а не только e.getMessage() (он бывает null,
 *     и тогда в ответе висело "tg_user_23": null).
 */
@Service
public class BroadcastService {

    private final Map<String, BaseConnector> connectors;

    private final UserRepository userTagRepository;
    ConfigService configService;
    MessageService messageService;

    public BroadcastService(MaxConnector maxConnector, TgConnector tgConnector, VkConnector vkConnector, UserRepository userTagRepository,MessageService messageService,ConfigService configService) {
        this.connectors = new HashMap<>();
        connectors.put("max",maxConnector);
        connectors.put("tg",tgConnector);
        connectors.put("vk",vkConnector);
        this.messageService = messageService;
        this.configService = configService;
        this.userTagRepository = userTagRepository;
    }

    public BroadcastResponse sendBroadcast(BroadcastRequest request) {
        Map<String, String> allErrors = new HashMap<>();

        // ---------- 1. валидация запроса ----------
        List<String> tags = request.getTags();
        if (tags == null || tags.isEmpty()) {
            allErrors.put("request", "Список тегов пуст: получателей выбрать некого. "
                    + "Передайте tags: [\"vip\", ...] или sendToAllConnectors + теги.");
            return new BroadcastResponse(0, 0, 0, allErrors);
        }
        // почистим пустые/пробельные теги — иначе JPQL ищет '' и находит мусор
        tags = tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();

        if (tags.isEmpty()) {
            allErrors.put("request", "Все переданные теги пустые");
            return new BroadcastResponse(0, 0, 0, allErrors);
        }

        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            if (request.getAttachments() == null || request.getAttachments().isBlank()) {
                allErrors.put("request", "Пустое сообщение: нет ни message, ни attachments");
                return new BroadcastResponse(0, 0, 0, allErrors);
            }
        }

        // ---------- 2. какие коннекторы используем ----------
        List<String> targetConnectors;
        if (request.isSendToAllConnectors()) {
            targetConnectors = connectors.keySet().stream().sorted().toList();
        } else {
            String type = request.getConnectorType();
            if (type == null || type.isBlank()) {
                allErrors.put("request", "Не указан connectorType (и sendToAllConnectors = false)");
                return new BroadcastResponse(0, 0, 0, allErrors);
            }
            type = type.toLowerCase().trim();
            if (!connectors.containsKey(type)) {
                allErrors.put("request", "Неизвестный тип коннектора: " + request.getConnectorType()
                        + ". Доступны: " + connectors.keySet());
                return new BroadcastResponse(0, 0, 0, allErrors);
            }
            targetConnectors = List.of(type);
        }

        // ---------- 3. получатели ----------
        int totalSuccess = 0;
        int totalFailed = 0;
        int totalRecipients = 0;

        for (String connectorType : targetConnectors) {
            BaseConnector connector = connectors.get(connectorType);

            List<Long> recipientIds;
            try {
                // userId конкретной соцсети: не шлём VK-аудиторию через TG и наоборот.
                recipientIds = userTagRepository.findUserIdsBySourceAndTags(connectorType, tags);
                System.out.println(recipientIds);
                System.out.println("TEST STES");
                System.out.println(connectorType);
                // ⬇ Если теги у вас общие на все платформы и нужен один список на всех —
                //   замените строку выше на:
                // recipientIds = userTagRepository.findUserIdsByTags(tags);
            } catch (Exception e) {
                allErrors.put(connectorType + "_query",
                        "Не удалось выбрать получателей по тегам " + tags + ": " + describe(e));
                continue;
            }

            if (recipientIds == null || recipientIds.isEmpty()) {
                allErrors.put(connectorType + "_no_recipients",
                        "По тегам " + tags + " в source='" + connectorType + "' никто не найден");
                continue;
            }

            totalRecipients += recipientIds.size();
            int success = 0;
            int failed = 0;

            for (Long userId : recipientIds) {
                try {
                    Long peerId = resolvePeerId(userId, connectorType);
                    connector.sendMessage(String.valueOf(peerId), message, request.getAttachments(), null);
                    success++;
                } catch (Exception e) {
                    failed++;
                    allErrors.put(connectorType + "_user_" + userId, describe(e));
                }
            }

            totalSuccess += success;
            totalFailed += failed;
        }

        return new BroadcastResponse(totalRecipients, totalSuccess, totalFailed, allErrors);
    }

    public long MaxUserIdToChatId(long userId){
        List<Message> messages = messageService.findBySourceAndUserId("max",userId);
        List<String> maxIds = configService.getConfig().getMax_ids();

        int i = 0;
        while(i < messages.size()){
            System.out.println(messages.get(i).getChatId());
            if(!maxIds.contains(String.valueOf(messages.get(i).getAuthorId()))){
                return messages.get(i).getChatId();
            }
            i++;
        }
        return Long.parseLong(maxIds.get(0));
    }
    /**
     * userId в таблице users — это уже id пользователя в соцсети (User.userId),
     * поэтому для VK он и есть peer_id личного диалога.
     *
     * Для TG: если вы храните user_id Telegram-аккаунта, а писать нужно в chat_id —
     * здесь нужен поиск в таблице chats: SELECT chat_id FROM chats WHERE source='tg' AND chat_id=...
     * Для бесед VK peer_id = 2000000000 + chat_id (см. VkConnector.toPeerId).
     */
    private Long resolvePeerId(Long userId, String type) {
        if(Objects.equals(type, "max")){
            return MaxUserIdToChatId(userId);
        }
        return userId;
    }

    /** e.getMessage() часто null — не кладём null в карту ошибок. */
    private static String describe(Throwable t) {
        if (t == null) {
            return "неизвестная ошибка";
        }
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        return (m != null && !m.isBlank()) ? m : c.getClass().getSimpleName();
    }
}