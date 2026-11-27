# nineouting-admin-service

관리자 페이지 백엔드 (REQ-214). 서비스 DB 5개(user, workspace, meeting, minutes, notification)의 표를 보고, 한 행씩 추가, 수정, 삭제한다. 화면은 front의 관리자 화면이다.

## 하는 일
- 관리자 로그인: 계정 하나. 5번 틀리면 15분 잠금. 로그인하면 HttpOnly 쿠키(8시간)
- 표 목록, 열 정보(형식, 기본 키, 허용 값 목록), 행 조회(쪽 나누기, 정렬, 검색)
  - 허용 값 목록: enum 형식이나 `CHECK (열 in (...))` 제약이 걸린 열의 값. 화면이 고르기 목록으로 보여 준다 (REQ-219)
- 빠른 작업 (REQ-218): 시험 계정 만들기(이메일 인증 없이), 사용자를 워크스페이스 멤버로 넣기. 표를 직접 고치지 않고 user-service, workspace-service의 내부 기능(`/internal/admin/...`)을 불러 비밀번호 해시, 중복 확인이 평소처럼 된다
- 한 행 추가, 수정, 삭제. 수정과 삭제는 기본 키로 정확히 한 행만 바꾼다
- 읽기만 하는 표: 배포 이력 표(`flyway_schema_history`), 기본 키가 없는 표
- 모든 추가, 수정, 삭제는 `admin.audit` 로그에 남긴다 (비밀번호, 토큰 열은 값을 가림)

## 안전장치
- 표와 열 이름은 DB 카탈로그에서 읽은 것만 SQL에 넣고, 값은 모두 매개변수로 넘긴다 (SQL 주입 방지)
- 읽기가 아닌 요청은 관리자 화면 머리글(`X-Requested-With: nineouting-admin`)이 있어야 한다 (CSRF 방지)
- 쿼리 하나는 15초를 넘으면 DB가 끊는다
- 계정, 비밀번호 해시, 쿠키 서명 키가 비어 있으면 시작하지 않는다

## 설정 (환경 변수)

| 이름 | 뜻 |
|---|---|
| `ADMIN_USERNAME` | 관리자 아이디 |
| `ADMIN_PASSWORD_HASH` | 관리자 비밀번호의 BCrypt 해시. 원문은 어디에도 두지 않는다 |
| `ADMIN_SESSION_SECRET` | 로그인 쿠키 서명 키 (32자 이상) |
| `ADMIN_ALLOWED_ORIGINS` | 관리자 화면 주소 (CORS). 기본 `https://dev.nepnep.app` |
| `ADMIN_COOKIE_SECURE` | HTTPS에서만 쿠키 (로컬 http면 `false`) |
| `USER_SERVICE_URL`, `WORKSPACE_SERVICE_URL` | 빠른 작업을 맡길 서비스 주소 (클러스터 안). 비어 있으면 그 작업은 쓸 수 없다 |
| `DB_<이름>_URL`, `DB_<이름>_USERNAME`, `DB_<이름>_PASSWORD` | 이름은 USER, WORKSPACE, MEETING, MINUTES, NOTIFICATION. 주소가 빈 DB는 목록에서 빠진다 |

DEV에서는 비밀값(쿠버네티스 Secret)으로 넣고, DB 비밀번호는 각 서비스의 기존 Secret을 그대로 쓴다.

BCrypt 해시 만들기 (예: Python):

```bash
python -c "import bcrypt; print(bcrypt.hashpw(b'<비밀번호>', bcrypt.gensalt()).decode())"
```

## 로컬 실행

```bash
./gradlew test      # Docker 필요 (Testcontainers)
./gradlew bootRun   # 위 환경 변수를 넣고
```

## 배포
다른 서비스와 같다. dev 머지 → 빌드, 테스트 → DEV ECR → infra 배포 PR → ArgoCD. 버전은 `v<다음 정식 버전>-dev.<순번>` 사전 릴리스.
