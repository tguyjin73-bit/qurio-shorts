"""
텔레그램 봇 알림 + 업로드 승인 요청 모듈.
인라인 버튼 (✅ 승인 / ❌ 거절)으로 업로드 전 사용자 확인.
"""

import time

import requests

TELEGRAM_API = "https://api.telegram.org/bot{token}"


def send_telegram(bot_token: str, chat_id: str, text: str) -> bool:
    """텔레그램 봇으로 일반 메시지 전송."""
    if not bot_token or not chat_id:
        return False

    url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
    payload = {
        "chat_id": chat_id,
        "text": text,
        "parse_mode": "HTML",
        "disable_web_page_preview": False,
    }
    try:
        resp = requests.post(url, json=payload, timeout=10)
        resp.raise_for_status()
        return True
    except Exception as e:
        print(f"[텔레그램] 전송 실패: {e}")
        return False


def send_approval_request(
    bot_token: str,
    chat_id: str,
    topic_title: str,
    script_preview: str,
    video_duration: float,
) -> str | None:
    """
    인라인 버튼이 포함된 업로드 승인 요청 메시지 전송.

    Returns:
        전송된 message_id (폴링에 사용) 또는 None
    """
    if not bot_token or not chat_id:
        return None

    preview = script_preview[:120] + "..." if len(script_preview) > 120 else script_preview
    text = (
        f"🎬 <b>새 YouTube Shorts 준비 완료!</b>\n\n"
        f"📌 <b>주제</b>: {topic_title}\n"
        f"⏱ <b>영상 길이</b>: 약 {video_duration:.0f}초\n\n"
        f"📝 <b>대본 미리보기</b>:\n{preview}\n\n"
        f"유튜브에 비공개로 업로드할까요?"
    )

    inline_keyboard = {
        "inline_keyboard": [[
            {"text": "✅ 승인하기", "callback_data": "approve_upload"},
            {"text": "❌ 거절하기", "callback_data": "reject_upload"},
        ]]
    }

    url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
    payload = {
        "chat_id": chat_id,
        "text": text,
        "parse_mode": "HTML",
        "reply_markup": inline_keyboard,
    }

    try:
        resp = requests.post(url, json=payload, timeout=10)
        resp.raise_for_status()
        data = resp.json()
        msg_id = str(data["result"]["message_id"])
        print(f"[텔레그램] 승인 요청 전송 완료 (message_id: {msg_id})")
        return msg_id
    except Exception as e:
        print(f"[텔레그램] 승인 요청 실패: {e}")
        return None


def poll_approval(
    bot_token: str,
    timeout_seconds: int = 600,
    poll_interval: int = 5,
    progress_callback=None,
) -> str:
    """
    텔레그램 getUpdates 폴링으로 사용자 승인 대기.

    Returns:
        "approved" | "rejected" | "timeout"
    """
    if not bot_token:
        return "timeout"

    url = f"https://api.telegram.org/bot{bot_token}/getUpdates"
    start = time.time()
    last_update_id = None

    # 기존 업데이트 소비 (처음에 한 번 호출해 현재 offset 파악)
    try:
        resp = requests.get(url, params={"limit": 1, "offset": -1}, timeout=10)
        data = resp.json()
        results = data.get("result", [])
        if results:
            last_update_id = results[-1]["update_id"]
    except Exception:
        pass

    while time.time() - start < timeout_seconds:
        elapsed = int(time.time() - start)
        remaining = timeout_seconds - elapsed
        if progress_callback:
            progress_callback(elapsed, remaining)

        try:
            params = {"limit": 10, "timeout": poll_interval}
            if last_update_id is not None:
                params["offset"] = last_update_id + 1

            resp = requests.get(url, params=params, timeout=poll_interval + 5)
            data = resp.json()
            updates = data.get("result", [])

            for update in updates:
                last_update_id = update["update_id"]
                callback_query = update.get("callback_query", {})
                callback_data = callback_query.get("data", "")

                if callback_data == "approve_upload":
                    # 버튼 응답 확인 메시지
                    _answer_callback(bot_token, callback_query.get("id", ""))
                    send_telegram(bot_token,
                                  str(callback_query.get("from", {}).get("id", "")),
                                  "✅ 승인됐습니다! 곧 YouTube에 업로드됩니다.")
                    return "approved"

                elif callback_data == "reject_upload":
                    _answer_callback(bot_token, callback_query.get("id", ""))
                    send_telegram(bot_token,
                                  str(callback_query.get("from", {}).get("id", "")),
                                  "❌ 업로드가 취소됐습니다.")
                    return "rejected"

        except Exception as e:
            print(f"[텔레그램] 폴링 오류: {e}")
            time.sleep(poll_interval)

    return "timeout"


def _answer_callback(bot_token: str, callback_query_id: str):
    """인라인 버튼 클릭 응답 처리 (로딩 스피너 제거)."""
    try:
        requests.post(
            f"https://api.telegram.org/bot{bot_token}/answerCallbackQuery",
            json={"callback_query_id": callback_query_id},
            timeout=5,
        )
    except Exception:
        pass


def notify_upload_complete(
    bot_token: str, chat_id: str, topic_title: str, youtube_id: str
) -> bool:
    """유튜브 업로드 완료 알림 (링크 포함)."""
    url = f"https://youtube.com/shorts/{youtube_id}"
    text = (
        f"🎉 <b>YouTube Shorts 업로드 완료!</b>\n\n"
        f"📌 주제: {topic_title}\n"
        f"🔗 링크: <a href='{url}'>{url}</a>\n\n"
        f"클릭해서 영상을 확인해보세요! (현재 비공개)\n"
        f"YouTube Studio에서 공개로 변경할 수 있습니다."
    )
    return send_telegram(bot_token, chat_id, text)
