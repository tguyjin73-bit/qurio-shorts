"""
Gemini API를 사용한 주제 동적 생성 및 스크립트 작성 모듈.
"""

import json
import random

import google.generativeai as genai

# ── 시스템 프롬프트 ───────────────────────────────────────────────────────────

TOPIC_SYSTEM_PROMPT = """당신은 제품개발 분야의 YouTube Shorts 콘텐츠 전략가이자 검증 전문가입니다.
채널 시청자: 하드웨어 스타트업 창업자, 기구개발자, 제품 기획자, 소프트웨어 개발자 중 하드웨어에 관심 있는 사람.

━━━ 좋은 주제의 기준 ━━━
① 실무 고충 해결 — "왜 이게 안 됐지?" 하고 막혔던 경험을 건드리는 주제
② 1분 안에 핵심 1가지를 설명할 수 있는 범위 (너무 광범위 X)
③ 기구개발·하드웨어 제품개발 특유의 내용 우선 (소프트웨어와 다른 어려움)
④ "이거 처음 듣는다" or "이거 나도 몰랐다" 반응이 나올 수 있는 구체적 팁

━━━ 주제 예시 (이 수준의 구체성이 필요) ━━━
좋은 예:
  - "금형 뽑기 전에 반드시 확인할 draft angle이란?"
  - "사출 성형에서 수축률 계산 안 하면 생기는 문제"
  - "PCB와 케이스 간 공차 설계, 얼마나 줘야 할까?"
  - "시제품 vs 목업 vs 양산 샘플, 뭐가 다른 걸까?"
  - "스타트업이 금형 투자 전 꼭 검증해야 하는 것"
  - "BOM이란 무엇이고 왜 처음부터 관리해야 하나?"
  - "기구 설계에서 리브(rib)를 추가하는 이유"

나쁜 예 (너무 광범위):
  - "제품개발 프로세스 소개" (1분에 담기엔 너무 광범위)
  - "스타트업 창업 팁" (제품개발과 무관)
  - "좋은 팀 구성법" (제품개발 실무 아님)

━━━ 자체 검증 절차 (주제 생성 후 2회 검토) ━━━
검토1: 이 주제가 '기구/하드웨어 제품개발' 실무자에게 진짜 필요한가?
검토2: 1분 안에 핵심 1가지를 완결되게 전달할 수 있는 범위인가?
두 검토 통과한 주제만 포함.

반드시 JSON 형식으로만 응답."""

SCRIPT_SYSTEM_PROMPT = """당신은 하드웨어 제품개발 전문 유튜버 스크립트 작가입니다.
제품개발·기구개발 실무자가 "맞아, 나도 이거 몰랐어!" 하고 공감하면서도 실제 도움이 되는 정보를 전달합니다.

━━━ 대본 작성 규칙 ━━━
1. 분량: 낭독 시 55~65초 (구어체 한국어 약 220~270자)
2. 문체: 일상 구어체 — "~하잖아요", "~거든요", "~알고 계셨나요?" 등 친근한 표현
3. 구성:
   - 훅: 시청자가 공감하거나 호기심이 생기는 질문·상황으로 시작 (예: "혹시 금형 설계하다가 이런 실수 해보셨나요?")
   - 핵심 포인트: 실무에서 바로 쓸 수 있는 구체적 정보 (숫자·사례 포함 권장)
   - 마무리: 1문장 핵심 정리 + 구독 유도 (강요 느낌 X, 자연스럽게)
4. 전문용어 처리: 첫 등장 시 쉬운 설명 병기 (예: "드래프트앵글, 쉽게 말하면 금형에서 제품이 잘 빠지도록 주는 기울기인데요")
5. 정보 밀도: 1분 안에 핵심 1가지를 완결되게 설명 (여러 주제 X)
6. 금지: 과장된 감탄사, 뜬구름 잡는 동기부여 멘트, 근거 없는 주장

━━━ 이미지 프롬프트 규칙 ━━━
- 성인 엔지니어·기획자 캐릭터 중심 (어린이·유아 캐릭터 절대 금지)
- 실제 작업 환경 묘사 (CAD 화면, 금형 부품, 회의실, 프로토타입 등)
- 3D CGI 전문가 애니메이션 스타일 (Pixar adult characters, realistic proportions)

반드시 JSON 형식으로만 응답"""

HOOK_GUIDE = {
    "question": "\"혹시 이런 경험 있으신가요?\" 형식의 공감형 질문으로 시작",
    "story": "\"실제로 있었던 일인데요\" 형식의 짧은 실패 또는 성공 사례로 시작",
    "listicle": "\"오늘 딱 3가지만 알려드릴게요\" 형식으로 예고하며 시작",
    "comparison": "\"A랑 B, 뭐가 다른지 아세요?\" 형식의 비교로 시작",
    "warning": "\"이거 모르면 나중에 크게 후회해요\" 형식의 경고로 시작",
    "persuasion": "\"왜 이게 중요한지 바로 알려드릴게요\" 형식",
    "tutorial": "\"단계별로 쉽게 알려드릴게요\" 형식",
}


def generate_topics_dynamic(
    api_key: str,
    model: str = "gemini-2.5-flash",
    used_titles: list[str] | None = None,
    count: int = 5,
) -> list[dict]:
    """
    Gemini가 제품개발 관련 주제 count개를 동적으로 생성.

    Args:
        used_titles: 이미 사용한 주제명 목록 (중복 방지)
        count: 생성할 주제 수

    Returns:
        [{"title": str, "category": str, "hook_type": str, "keywords": [str]}]
    """
    genai.configure(api_key=api_key)
    model_obj = genai.GenerativeModel(model)

    used_str = ""
    if used_titles:
        used_str = f"\n\n이미 사용한 주제 (중복 금지):\n" + "\n".join(f"- {t}" for t in used_titles[-30:])

    prompt = f"""{TOPIC_SYSTEM_PROMPT}
{used_str}

[지시사항]
하드웨어 제품개발 YouTube Shorts 주제 {count}개를 생성해주세요.

카테고리 가중치 (기구/하드웨어 비중을 높게):
- 기구개발 (30%): 공차, 리브, 보스, 스냅핏, 언더컷, 조립공차, 재료선택 등
- 금형/양산 (25%): 금형비용, 수축률, draft angle, 게이트, 파팅라인, DFM 등
- 목업/프로토타입 (20%): FDM/SLA/SLS 차이, 재료, 표면처리, 목업→양산 전환 등
- PM/기획 (15%): BOM, ECO, DVT/EVT/PVT, 일정관리, 협력사 관리 등
- 펌웨어/소프트웨어 (10%): 임베디드 기초, MCU 선택, 하드웨어-펌웨어 협업 등

각 주제 제목은:
- 구체적인 기술 용어 + 실무 상황 조합 (예: "금형 설계 전 draft angle 이해하기")
- 시청자가 "이거 나도 궁금했는데" 반응을 끌어낼 것
- 1분 영상 범위에 맞게 좁고 구체적일 것

훅 유형: question, story, listicle, comparison, warning, persuasion, tutorial

JSON 구조로만 응답:
{{
  "topics": [
    {{
      "title": "주제 제목 (30자 이내)",
      "category": "카테고리명",
      "hook_type": "훅 유형",
      "keywords": ["키워드1", "키워드2", "키워드3"],
      "target_pain": "시청자가 겪는 구체적 어려움 한 줄 설명"
    }}
  ]
}}"""

    try:
        response = model_obj.generate_content(
            prompt,
            generation_config=genai.GenerationConfig(
                response_mime_type="application/json",
                max_output_tokens=8192,
            ),
        )
        data = json.loads(response.text)
        topics = data.get("topics", [])
        return topics[:count]
    except Exception as e:
        print(f"[ScriptGen] 주제 생성 오류: {e}")
        # fallback: 기본 주제 몇 개 반환
        return _fallback_topics(count)


def generate_script(
    topic: dict,
    api_key: str,
    model: str = "gemini-2.5-flash",
) -> dict:
    """
    Gemini로 YouTube Shorts 스크립트 + 이미지 프롬프트 JSON 생성.

    Args:
        topic: {"title", "category", "hook_type", "keywords"}

    Returns:
        {title, description, tags, segments, full_script, image_prompts}
    """
    genai.configure(api_key=api_key)
    model_obj = genai.GenerativeModel(model)

    hook_type = topic.get("hook_type", "question")
    hook_guide = HOOK_GUIDE.get(hook_type, "")
    keywords = topic.get("keywords", [])
    category = topic.get("category", "제품개발")

    target_pain = topic.get("target_pain", "")
    prompt = f"""{SCRIPT_SYSTEM_PROMPT}

[주제 정보]
제목: {topic['title']}
카테고리: {category}
키워드: {', '.join(keywords)}
시청자 고충: {target_pain}
훅 유형: {hook_type} — {hook_guide}

[대본 작성 지침]
- 훅에서 시청자가 겪었을 법한 상황 또는 질문을 먼저 던질 것
- 핵심 포인트마다 구체적 수치/사례 포함 권장 (예: "3도 이상 줘야 한다", "보통 0.3~0.5mm 공차를")
- 쉬운 한국어로, 전문용어 첫 등장 시 괄호 설명 추가
- 전체 글자 수 220~270자 (TTS 기준 55~65초)

JSON 구조로만 응답:

{{
  "title": "YouTube 제목 (30자 이내, 숫자·이모지 포함)",
  "description": "YouTube 설명 (150자 이내, 핵심 내용 + 해시태그)",
  "tags": ["태그1", "태그2", "태그3", "태그4", "태그5"],
  "segments": [
    {{"type": "hook", "text": "도입부 (2-3문장): 시청자가 공감할 질문 또는 상황으로 시작"}},
    {{"type": "point", "index": 1, "text": "핵심1 (2-3문장): 구체적 정보, 수치 or 사례 포함"}},
    {{"type": "point", "index": 2, "text": "핵심2 (2-3문장): 핵심1을 심화하거나 다른 각도의 실용 정보"}},
    {{"type": "point", "index": 3, "text": "핵심3 (2-3문장): 실무 적용 팁 or 자주 하는 실수 경고"}},
    {{"type": "cta", "text": "마무리 (1-2문장): 핵심 한 줄 정리 + 자연스러운 구독 유도"}}
  ],
  "full_script": "위 세그먼트를 이어붙인 전체 대본 (220~270자, TTS 낭독용)",
  "image_prompts": [
    "hook scene: professional 3D CGI, adult Korean engineer/designer in real workspace, [장면 영어 설명, 50단어 이내]",
    "point1 scene: professional 3D CGI, adult characters, engineering/product context, [장면 영어 설명]",
    "point2 scene: professional 3D CGI, adult characters, [장면 영어 설명]",
    "point3 scene: professional 3D CGI, adult characters, [장면 영어 설명]",
    "cta scene: professional 3D CGI, adult characters, positive conclusion, [장면 영어 설명]"
  ]
}}

image_prompts는 Gemini Imagen에 직접 넣을 영어 프롬프트입니다.
반드시 성인 캐릭터만 등장시키고, 실제 제품개발·기구개발 작업 환경을 시각화하세요."""

    def _repair_json(text: str) -> dict:
        """잘린 JSON을 자동 복구해서 파싱."""
        # 1. JSON 블록 추출 (```json ... ``` 또는 { ... })
        start = text.find("{")
        if start == -1:
            raise ValueError("JSON 블록 없음")
        text = text[start:]

        # 2. 따옴표가 닫히지 않은 경우 → 마지막 완전한 필드까지 자르기
        # 마지막 쉼표 이후 잘린 필드 제거
        last_comma = max(text.rfind(",\n"), text.rfind(", "))
        last_close = max(text.rfind("}"), text.rfind("]"))

        if last_comma > last_close and last_comma > 0:
            text = text[:last_comma]

        # 3. 열린 괄호 자동 닫기
        stack = []
        in_str = False
        escape = False
        for ch in text:
            if escape:
                escape = False
                continue
            if ch == "\\" and in_str:
                escape = True
                continue
            if ch == '"':
                in_str = not in_str
                continue
            if not in_str:
                if ch in "{[":
                    stack.append("}" if ch == "{" else "]")
                elif ch in "}]":
                    if stack and stack[-1] == ch:
                        stack.pop()

        # 문자열이 열린 채로 끝난 경우 닫기
        if in_str:
            text += '"'
        # 닫히지 않은 괄호 닫기
        for closer in reversed(stack):
            text += closer

        return json.loads(text)

    # 최대 3회 재시도 (JSON 잘림 방지)
    last_err = None
    for attempt in range(3):
        try:
            response = model_obj.generate_content(
                prompt,
                generation_config=genai.GenerationConfig(
                    response_mime_type="application/json",
                    max_output_tokens=8192,
                ),
            )
            # 1차: 정상 파싱
            try:
                return json.loads(response.text)
            except json.JSONDecodeError as e:
                last_err = e
                print(f"[ScriptGen] JSON 파싱 오류 (시도 {attempt+1}/3): {e}")
                # 2차: 자동 복구 시도
                try:
                    result = _repair_json(response.text)
                    # full_script가 없으면 segments에서 조합
                    if "segments" in result and not result.get("full_script"):
                        result["full_script"] = " ".join(
                            s.get("text", "") for s in result["segments"]
                        )
                    print(f"[ScriptGen] JSON 복구 성공 (시도 {attempt+1}/3)")
                    return result
                except Exception as repair_err:
                    print(f"[ScriptGen] JSON 복구 실패: {repair_err}")
                    if attempt < 2:
                        import time
                        time.sleep(3)
                    continue

        except Exception as e:
            last_err = e
            print(f"[ScriptGen] 대본 생성 오류 (시도 {attempt+1}/3): {e}")
            if attempt < 2:
                import time
                time.sleep(3)

    raise RuntimeError(f"대본 생성 실패: {last_err}")


def _fallback_topics(count: int) -> list[dict]:
    """API 오류 시 사용할 기본 주제 목록 (구체적·실무적)."""
    base = [
        {
            "title": "금형에서 draft angle을 꼭 줘야 하는 이유",
            "category": "금형/양산", "hook_type": "warning",
            "keywords": ["금형", "드래프트앵글", "사출성형"],
            "target_pain": "금형 뽑고 나서 제품이 안 빠지거나 스크래치 나는 문제",
        },
        {
            "title": "사출 성형 수축률 계산, 왜 중요한가?",
            "category": "금형/양산", "hook_type": "question",
            "keywords": ["수축률", "사출성형", "치수공차"],
            "target_pain": "도면 치수와 실제 제품 치수가 달라서 조립이 안 되는 상황",
        },
        {
            "title": "기구 설계에서 보스(boss)를 쓰는 이유",
            "category": "기구개발", "hook_type": "tutorial",
            "keywords": ["보스", "기구설계", "나사체결"],
            "target_pain": "케이스 조립 시 나사 체결부 설계를 어떻게 해야 할지 모름",
        },
        {
            "title": "FDM, SLA, SLS — 프로토타입 출력 방식 차이",
            "category": "목업/프로토타입", "hook_type": "comparison",
            "keywords": ["3D프린팅", "FDM", "SLA", "SLS"],
            "target_pain": "어떤 방식으로 시제품을 출력해야 할지 판단이 안 됨",
        },
        {
            "title": "BOM(부품표)을 처음부터 관리해야 하는 이유",
            "category": "PM/기획", "hook_type": "warning",
            "keywords": ["BOM", "부품표", "원가관리"],
            "target_pain": "개발 후반에 부품 정리가 안 돼서 원가 계산이 뒤죽박죽이 됨",
        },
        {
            "title": "언더컷(undercut)이 금형 비용을 올리는 원리",
            "category": "금형/양산", "hook_type": "story",
            "keywords": ["언더컷", "슬라이드코어", "금형비"],
            "target_pain": "설계는 했는데 금형 견적이 예상보다 훨씬 높게 나옴",
        },
        {
            "title": "PCB와 케이스 조립 공차, 얼마나 줘야 할까?",
            "category": "기구개발", "hook_type": "question",
            "keywords": ["조립공차", "PCB", "케이스설계"],
            "target_pain": "PCB가 케이스 안에 안 들어가거나 너무 헐렁한 문제",
        },
        {
            "title": "EVT, DVT, PVT — 양산 전 단계가 왜 필요한가?",
            "category": "PM/기획", "hook_type": "tutorial",
            "keywords": ["EVT", "DVT", "PVT", "양산검증"],
            "target_pain": "시제품은 됐는데 양산에서 문제가 터지는 경험",
        },
    ]
    random.shuffle(base)
    return (base * ((count // len(base)) + 1))[:count]
