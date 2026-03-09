import json
import random


class TopicSelector:
    def __init__(self, topics_file: str, avoid_repeat_count: int = 20):
        self.topics_file = topics_file
        self.avoid_repeat_count = avoid_repeat_count
        self._data = self._load()

    def _load(self) -> dict:
        with open(self.topics_file, "r", encoding="utf-8") as f:
            return json.load(f)

    def _save(self):
        with open(self.topics_file, "w", encoding="utf-8") as f:
            json.dump(self._data, f, ensure_ascii=False, indent=2)

    def select(self) -> tuple[dict, dict]:
        """미사용 주제 중 랜덤 선택. (topic, category) 튜플 반환."""
        used_ids = set(self._data.get("used_topic_ids", []))

        # 전체 주제 목록 수집
        all_topics = []
        for cat in self._data["categories"]:
            for topic in cat["topics"]:
                all_topics.append((topic, cat))

        # 미사용 주제 필터링
        unused = [(t, c) for t, c in all_topics if t["id"] not in used_ids]

        # 모두 사용했으면 이력 초기화
        if not unused:
            self._data["used_topic_ids"] = []
            self._save()
            unused = all_topics

        topic, category = random.choice(unused)
        return topic, category

    def mark_used(self, topic_id: str):
        """사용한 주제 ID를 기록하고 avoid_repeat_count 초과 시 오래된 것 제거."""
        used = self._data.setdefault("used_topic_ids", [])
        if topic_id not in used:
            used.append(topic_id)
        # 최대 개수 유지
        if len(used) > self.avoid_repeat_count:
            self._data["used_topic_ids"] = used[-self.avoid_repeat_count:]
        self._save()
