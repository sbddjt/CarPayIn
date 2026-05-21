\# 테스트 코드 작성 컨벤션



\## 0. 파일명



테스트 파일명에는 유스케이스 번호를 포함한다. 파일명만 보고 어떤 유스케이스 테스트인지 알 수 있어야 한다.



| 구분 | 원칙 |

| --- | --- |

| 테스트 파일명 | UC 번호 포함 |

| 구현 파일명 | 기능명 중심 |

| 문서, 테스트 class, docstring | UC 번호 명시 |



테스트 파일명 형식:



```text

test\_uc\_{domain}\_{number}\_{use\_case\_name}.py

```



예:



```text

test\_uc\_auth\_001\_create\_qr\_session.py

test\_uc\_auth\_005\_confirm\_vehicle.py

test\_uc\_auth\_006\_refresh\_access\_token.py

test\_uc\_park\_001\_register\_pre\_notify.py

test\_uc\_pay\_001\_get\_parking\_fee.py

```



구현 파일명은 UC 번호보다 기능명을 우선한다. 구현 코드는 다른 코드에서 import되므로, 유스케이스 번호를 파일명에 넣기보다 명확한 기능명으로 작성한다.



예:



```text

create\_qr\_session.py

confirm\_vehicle.py

refresh\_access\_token.py

register\_pre\_notify.py

get\_parking\_fee.py

```



\## 1. 테스트는 레이어별로 분리한다



테스트 코드는 목적에 따라 아래처럼 나누어 작성한다.



| 테스트 종류 | 검증 대상 | 작성 위치 | 주요 내용 |

| --- | --- | --- | --- |

| Unit Test | 비즈니스 로직 | `tests/unit/` | use case service의 규칙 검증 |

| API Test | HTTP API 계약 | `tests/integration/api/` | endpoint, request, response, status code 검증 |

| Integration Test | 실제 인프라 연동 | `tests/integration/` | DB, Redis, 외부 client 연동 검증 |

| E2E Test | 전체 사용자 흐름 | `tests/e2e/` | 핵심 성공 시나리오 검증 |



하나의 테스트 파일에서 위 책임들을 섞지 않는다.



\## 2. Unit Test 기준



Unit test는 `test\_create\_qr\_session.py`의 형식을 기준으로 작성한다.



Unit test에서는 FastAPI `TestClient`, 실제 DB, 실제 Redis, 실제 외부 API를 사용하지 않는다.



대신 `app.application` 계층의 use case service를 직접 실행한다.



기본 흐름은 아래와 같다.



```text

Command 생성

\-> Service.execute(command) 호출

\-> Result 또는 Fake 객체 상태 검증

```



예시:



```python

command = CreateQrSessionCommand(

&#x20;   login\_session\_id=VALID\_SESSION\_ID,

&#x20;   vin\_hash=VALID\_VIN\_HASH,

)



result = create\_qr\_session\_service.execute(command)



assert result.login\_url == expected\_url

```



\## 3. 테스트 파일 구조



테스트 파일은 아래 순서로 작성한다.



```text

1\. 파일 docstring

2\. import

3\. 테스트 상수

4\. Fake 클래스

5\. pytest fixture

6\. 테스트 클래스

7\. 테스트 함수

```



예시:



```python

"""

QR 로그인 / 현대 OAuth 유스케이스 단위 테스트

UC-AUTH-001: QR 로그인 세션 생성

"""



import pytest



from app.application.auth.create\_qr\_session import (

&#x20;   CreateQrSessionCommand,

&#x20;   CreateQrSessionService,

)





VALID\_SESSION\_ID = "sess-001"

VALID\_VIN\_HASH = "vin-hash-001"

PUBLIC\_BASE\_URL = "https://api.carpayin.test"

```



\## 4. 유스케이스와 API 경로 명시



테스트 파일 상단에는 UC 번호와 이름을 적는다.



테스트 클래스 docstring에는 관련 API 경로를 적는다.



```python

class TestCreateQrSession:

&#x20;   """UC-AUTH-001 - POST /auth/qr-session"""

```



API 경로는 반드시 최신 문서를 기준으로 한다.



기준 문서:



```text

docs/use-cases/

docs/api/car-pay-in-openapi.yaml

```



테스트 파일에 남아 있는 오래된 주석이나 docstring은 기준으로 삼지 않는다.



\## 5. 테스트 데이터 상수 규칙



테스트 데이터는 파일 상단에 상수로 선언한다.



```python

VALID\_SESSION\_ID = "sess-001"

VALID\_VIN\_HASH = "vin-hash-001"

PUBLIC\_BASE\_URL = "https://api.carpayin.test"

```



상수 이름은 의미가 드러나게 작성한다.



| Prefix | 의미 |

| --- | --- |

| `VALID\_` | 정상 입력값 |

| `INVALID\_` | 잘못된 입력값 |

| `EXPIRED\_` | 만료된 값 |

| `OTHER\_` | 다른 사용자, 차량, 세션 등 비교용 값 |



테스트 본문 안에서 같은 문자열이나 숫자를 반복해서 직접 쓰지 않는다.



\## 6. Fake 클래스 작성 규칙



Unit test에서는 실제 DB, Redis, 외부 API 대신 Fake 클래스를 사용한다.



Fake 클래스 이름은 아래처럼 작성한다.



```text

Fake{역할}

```



예시:



```python

class FakeQrSessionStore:

&#x20;   def \_\_init\_\_(self):

&#x20;       self.saved\_sessions = {}



&#x20;   def save\_pending\_session(

&#x20;       self,

&#x20;       \*,

&#x20;       session\_id: str,

&#x20;       vin\_hash: str,

&#x20;       ttl\_seconds: int,

&#x20;   ):

&#x20;       self.saved\_sessions\[session\_id] = {

&#x20;           "session\_id": session\_id,

&#x20;           "vin\_hash": vin\_hash,

&#x20;           "status": "pending",

&#x20;           "ttl\_seconds": ttl\_seconds,

&#x20;       }

```



Fake는 실제 인프라를 완전히 구현하지 않는다. 테스트에 필요한 동작만 메모리의 `dict`나 `list`로 구현한다.



외부 호출 여부를 검증해야 하면 `\*\_calls` 리스트에 저장한다.



\## 7. Fixture 작성 규칙



Fake 객체와 service 객체는 `pytest.fixture`로 만든다.



```python

@pytest.fixture

def fake\_qr\_session\_store():

&#x20;   return FakeQrSessionStore()





@pytest.fixture

def create\_qr\_session\_service(fake\_qr\_session\_store):

&#x20;   return CreateQrSessionService(

&#x20;       qr\_session\_store=fake\_qr\_session\_store,

&#x20;       public\_base\_url=PUBLIC\_BASE\_URL,

&#x20;   )

```



fixture 이름은 역할이 보이게 작성한다.



```text

fake\_qr\_session\_store

create\_qr\_session\_service

```



테스트 함수는 fixture를 인자로 받아 사용한다.



\## 8. 테스트 클래스와 함수명



테스트 클래스 이름은 유스케이스 이름에 맞춘다.



```python

class TestCreateQrSession:

&#x20;   ...

```



테스트 함수명은 영어 snake\_case를 사용한다.



```python

def test\_valid\_request\_stores\_pending\_session(...):

&#x20;   ...



def test\_empty\_session\_id\_raises\_error(...):

&#x20;   ...

```



하나의 테스트 함수는 하나의 동작 또는 하나의 실패 조건만 검증한다.



좋은 예:



```text

정상 요청이면 pending 세션을 저장한다

정상 요청이면 login\_url을 반환한다

session\_id가 비어 있으면 실패한다

vin\_hash가 비어 있으면 실패한다

```



나쁜 예:



```text

정상 요청의 모든 결과를 한 테스트에서 전부 검증한다

성공 케이스와 실패 케이스를 한 테스트에 섞는다

```



\## 9. 테스트 본문 작성 흐름



테스트 본문은 Arrange, Act, Assert 흐름으로 작성한다.



```python

def test\_valid\_request\_stores\_pending\_session(

&#x20;   self,

&#x20;   create\_qr\_session\_service,

&#x20;   fake\_qr\_session\_store,

):

&#x20;   """유효한 요청이면 QR 세션을 pending 상태로 저장한다."""

&#x20;   command = CreateQrSessionCommand(

&#x20;       login\_session\_id=VALID\_SESSION\_ID,

&#x20;       vin\_hash=VALID\_VIN\_HASH,

&#x20;   )



&#x20;   create\_qr\_session\_service.execute(command)



&#x20;   saved = fake\_qr\_session\_store.saved\_sessions\[VALID\_SESSION\_ID]

&#x20;   assert saved\["session\_id"] == VALID\_SESSION\_ID

&#x20;   assert saved\["vin\_hash"] == VALID\_VIN\_HASH

&#x20;   assert saved\["status"] == "pending"

&#x20;   assert saved\["ttl\_seconds"] == 15 \* 60

```



성공 케이스에서는 반환값뿐 아니라 side effect도 검증한다.



예:



```text

Redis에 저장되어야 하는 값

DB에 생성되어야 하는 값

외부 client가 호출되어야 하는지 여부

반환 result 값

```



\## 10. 실패 케이스 작성 규칙



Unit test에서는 `pytest.raises`로 service error를 검증한다.



```python

with pytest.raises(ValueError) as exc\_info:

&#x20;   create\_qr\_session\_service.execute(command)



assert str(exc\_info.value) == "login\_session\_id is required"

```



Unit test에서는 HTTP status code를 직접 검증하지 않는다.



예를 들어 유스케이스 문서에 "400 반환"이라고 되어 있어도 unit test에서는 service error만 확인한다. HTTP 400으로 변환되는지는 API test에서 따로 검증한다.



\## 11. API Test 기준



API test는 실제 HTTP endpoint를 호출해서 API 계약을 검증한다.



검증 대상:



```text

endpoint 경로

request body

response body

status code

auth guard

request validation

```



예:



```text

POST /auth/qr-session 정상 요청 시 200 반환

응답에 login\_url 포함

login\_session\_id 누락 시 400 반환

vin\_hash 누락 시 400 반환

```



API test에서는 unit test에서 검증한 비즈니스 세부 규칙을 모두 반복하지 않는다.



\## 12. Integration Test 기준



Integration test는 실제 인프라 연동을 검증한다.



검증 대상:



```text

DB 저장/조회

Redis 저장/조회/TTL

외부 client adapter 요청 변환

transaction 처리

```



Unit test에서 Fake로 대체했던 부분이 실제 구현체에서도 동작하는지 확인한다.



\## 13. E2E Test 기준



E2E test는 전체 사용자 흐름을 검증한다.



예:



```text

QR 세션 생성

\-> 현대 OAuth 시작

\-> callback 처리

\-> 차량 선택 확정

\-> app token 발급

```



E2E test는 느리고 유지보수 비용이 크므로 핵심 성공 시나리오 위주로 최소한만 작성한다.



\## 14. 최종 원칙



비즈니스 규칙은 unit test에서 검증한다.



HTTP status code와 request/response는 API test에서 검증한다.



실제 DB, Redis, 외부 client 연동은 integration test에서 검증한다.



전체 사용자 흐름은 E2E test에서 최소한만 검증한다.



Unit test는 `test\_create\_qr\_session.py`처럼 Fake dependency, pytest fixture, Command-Service-Result 구조를 기준으로 작성한다.



