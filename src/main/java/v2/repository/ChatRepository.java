package v2.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import v2.entity.Chat;
import java.util.Optional;

@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {

    Optional<Chat> findByChatId(Long chatId);

    default Chat saveOrUpdate(Chat chat) {
        Optional<Chat> existing = findByChatId(chat.getChatId());
        if (existing.isPresent()) {
            Chat found = existing.get();
            found.setTitle(chat.getTitle());
            found.setGroup(chat.isGroup());
            return save(found);
        }
        return save(chat);
    }

    @Query("SELECT c FROM Chat c LEFT JOIN Message m ON c.chatId = m.chatId " +
            "WHERE m.timestamp = (SELECT MAX(m2.timestamp) FROM Message m2 WHERE m2.chatId = c.chatId) " +
            "OR NOT EXISTS (SELECT 1 FROM Message m3 WHERE m3.chatId = c.chatId)")
    Page<Chat> findAllWithLastMessage(Pageable pageable);
}