package v2.services;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import v2.dto.PaginatedResponse;
import v2.entity.Chat;
import v2.entity.Message;
import v2.repository.ChatRepository;
import v2.repository.MessageRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ChatService {
    private final ChatRepository chatRepo;
    private final MessageRepository msgRepo;

    public ChatService(ChatRepository chatRepo, MessageRepository msgRepo) {
        this.chatRepo = chatRepo;
        this.msgRepo = msgRepo;
    }

    // 1. Получить список всех чатов для левой панели
    // ВАЖНО: возвращает DTO с последним сообщением!
    public List<ChatDto> getAllChatsForFrontend() {
        List<Chat> chats = chatRepo.findAll();

        return chats.stream()
                .map(chat -> {
                    // Найти последнее сообщение для этого чата
                    List<Message> messages = msgRepo.findByChatIdOrderByTimestampDesc(chat.getChatId());
                    Message lastMsg = messages.isEmpty() ? null : messages.get(0);

                    return new ChatDto(
                            chat,
                            lastMsg != null ? lastMsg.getText() : null,
                            lastMsg != null ? lastMsg.getTimestamp() : null
                    );
                })
                .sorted((a, b) -> Long.compare(
                        b.timestamp != null ? b.timestamp() : 0,
                        a.timestamp() != null ? a.timestamp() : 0
                ))
                .toList();
    }

    public PaginatedResponse<ChatDto> getChatsPaginated(int page, int size) {
        // Сортировка по timestamp последнего сообщения (DESC)
        Sort sort = Sort.by(Sort.Direction.DESC, "timestamp");
        PageRequest pageRequest = PageRequest.of(page, size, sort);

        Page<Chat> chatPage = chatRepo.findAllWithLastMessage(pageRequest);

        List<ChatDto> dtos = chatPage.getContent().stream()
                .map(chat -> {
                    // Оптимизация: берем только последнее сообщение одним запросом
                    Optional<Message> lastMsg = msgRepo.findTopByChatIdOrderByTimestampDesc(chat.getChatId());

                    return new ChatDto(
                            chat,
                            lastMsg.map(Message::getText).orElse(null),
                            lastMsg.map(Message::getTimestamp).orElse(null)
                    );
                })
                .collect(Collectors.toList());

        return new PaginatedResponse<>(
                dtos,
                chatPage.getNumber(),
                chatPage.getSize(),
                chatPage.getTotalElements(),
                chatPage.getTotalPages(),
                chatPage.hasNext(),
                chatPage.hasPrevious()
        );
    }

    // 2. Получить чат по ID
    public Optional<Chat> getChat(Long chatId) {
        return chatRepo.findByChatId(chatId);
    }

    // 3. Создать/обновить чат (используется коннекторами)
    public Chat saveOrUpdate(Chat chat) {
        return chatRepo.saveOrUpdate(chat);
    }

    // DTO для фронта
    public record ChatDto(Chat chat, String lastMessage, Long timestamp) {
        public Long getChatId() { return chat.getChatId(); }
        public String getTitle() { return chat.getTitle(); }
        public boolean isGroup() { return chat.isGroup(); }
        public String getSource() { return chat.getSource(); }
    }
}