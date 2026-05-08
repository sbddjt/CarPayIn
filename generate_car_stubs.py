"""
android.car 최소 스텁 JAR 생성 스크립트
========================================
VehicleDataManager.kt 컴파일에 필요한 android.car.* 클래스 스텁을 만듭니다.
javac 없이 Python만으로 실행 가능합니다.

실행 방법:
  python generate_car_stubs.py

생성 위치: app/libs/android.car.jar
"""
import struct, zipfile, os

def make_class(fqname, super_name="java/lang/Object"):
    cp, strings = [], {}

    def utf8(s):
        if s in strings: return strings[s]
        idx = len(cp) + 1
        b = s.encode("utf-8")
        cp.append(struct.pack(">BH", 1, len(b)) + b)
        strings[s] = idx
        return idx

    def cls(name):
        ni = utf8(name)
        idx = len(cp) + 1
        cp.append(struct.pack(">BH", 7, ni))
        return idx

    this_cls  = cls(fqname)
    super_cls = cls(super_name)
    utf8("Code")

    cp_bytes = b"".join(cp)
    body = struct.pack(">HHH", 0x0021, this_cls, super_cls)  # ACC_PUBLIC|ACC_SUPER
    body += struct.pack(">HHHH", 0, 0, 0, 0)                 # iface/field/method/attr counts

    return (struct.pack(">IHH", 0xCAFEBABE, 0, 55)           # magic, minor=0, major=55(Java11)
            + struct.pack(">H", len(cp) + 1)
            + cp_bytes + body)

classes = [
    ("android/car/Car",                                             "java/lang/Object"),
    ("android/car/VehicleGear",                                     "java/lang/Object"),
    ("android/car/hardware/CarPropertyValue",                       "java/lang/Object"),
    ("android/car/hardware/property/CarPropertyManager",            "java/lang/Object"),
    ("android/car/hardware/property/CarPropertyManager$CarPropertyEventCallback",
                                                                    "java/lang/Object"),
]

out = os.path.join(os.path.dirname(__file__), "app", "libs", "android.car.jar")
os.makedirs(os.path.dirname(out), exist_ok=True)

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    for name, sup in classes:
        zf.writestr(name + ".class", make_class(name, sup))
        print(f"  + {name}")

print(f"\n생성 완료: {out}  ({os.path.getsize(out):,} bytes)")
print("이제 Android Studio에서 Sync Project를 누르세요.")
