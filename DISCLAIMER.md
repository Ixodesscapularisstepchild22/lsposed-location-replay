**English** | [简体中文](DISCLAIMER.zh-CN.md)

# Disclaimer and Scope of Use

**Please read this document in full before using LocRec. By downloading, installing, or using this project, you acknowledge that you have read, understood, and agreed to the following terms.**

---

> [!CAUTION]
> ## 1. Prohibition: do not use this project to forge Chaoxing Learning check-ins
>
> **It is strictly forbidden to use this project to forge check-ins, attendance, clock-ins, or any other form of presence verification in Chaoxing Learning (超星学习通, `com.chaoxing.mobile`).**
>
> Chaoxing Learning is the primary **test target** of this project, and testing is limited to the
> compatibility and behaviour analysis of location functionality. Using this project to forge
> check-ins, proxy sign-ins, or comparable conduct is unrelated to the project's objectives,
> violates the target service's terms, and may be unlawful. Account bans, academic penalties,
> civil claims, administrative penalties, or criminal liability arising from such use are borne
> solely by the user.
>
> This project offers no capability commitment or technical support for such uses, and makes no
> guarantee regarding their outcome.

---

## 2. Nature of the Project

LocRec is a **location-signal recording and replay tool**. It is intended for use on devices that the developer **owns or has been expressly authorized to use**, in order to record the location environment actually received by the device (GPS / network location fixes, WiFi scans, cell-tower information, NMEA, satellite status) and to replay that recording to applications within a specified scope.

Its design objective is **technical research into location-related functionality**, including but not limited to:

- compatibility testing and regression verification of location features
- behavioral analysis of application location pipelines
- research into location-data minimization and privacy protection
- verification of location-environment consistency in controlled experiments

## 3. Expressly Prohibited Uses

You **must not** use this project for:

- **forging check-ins, attendance, clock-ins, or classroom presence verification in Chaoxing Learning or any comparable platform** (see the prohibition above)
- circumventing examinations, attendance, clock-in, check-in, or any other form of identity or presence verification
- deceiving others (including but not limited to location deception in social, dating, or transactional contexts)
- circumventing or defeating the risk-control, anti-cheat, or anti-fraud mechanisms of third-party services
- forging location evidence in insurance, claims, logistics, ride-hailing, shared-mobility, or comparable contexts
- any conduct that violates the laws and regulations of your jurisdiction, or the terms of service of any target service
- deploying or using it on devices that you do **not own and for which you have not obtained express authorization**

The foregoing uses are unrelated to the project's objectives and may constitute unlawful or contractual violations.

## 4. User Responsibility

- **Legal compliance is your responsibility**: You are responsible for determining the restrictions that the laws of your jurisdiction impose on location simulation, system modification, and reverse engineering, and you are responsible for all of your use.
- **Authorization is your responsibility**: You must obtain written authorization before using this project on devices belonging to others, corporate devices, or production environments.
- **Assumption of consequences**: Account bans, service termination, civil claims, administrative penalties, or criminal liability arising from your use of this project shall be borne solely by the user.

## 5. No Warranty

This project is provided **"AS IS"**, without any express or implied warranties, including but not limited to the warranties of merchantability, fitness for a particular purpose, and non-infringement. The authors and contributors shall not be liable for any direct, indirect, incidental, special, or punitive damages. For the complete terms, see Sections 7 and 8 of the [LICENSE](./LICENSE).

## 6. Technical and Compliance Risk Notice

- This module requires **LSPosed**, or a comparable runtime injection framework. Modifying system behavior may render the device unstable, cause applications to crash, or result in data loss.
- Some applications are capable of runtime-environment detection (injection-framework detection, debug-state detection, system-consistency verification, and the like). Use of this module may cause such applications to refuse service or to flag the account.
- Some applications perform **server-side** cross-validation of WiFi, cell-tower, IP, and historical trajectory signals. Client-side self-consistency does not mean that the server cannot identify the change, and this project makes no guarantee regarding any detection outcome.
- This project **does not provide** any promise of capability, or technical support, for circumventing the above detections.

## 7. Data and Privacy

- Recorded data is stored only on the device itself; the module contains no network requests and no telemetry.
- The recorded dataset contains real coordinates, WiFi fingerprints, and cell-tower information, which constitute **sensitive personal data**. Do not commit it to public repositories, do not share it with others, and delete it promptly when it is no longer needed.
- This repository contains no recorded data.

## 8. Effectiveness

This disclaimer is released together with the project. The project provides no technical support and makes no response to, or endorsement of, any user's conduct.
