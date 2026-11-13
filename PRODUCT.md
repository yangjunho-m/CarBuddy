# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Pleos Connect용 네이티브 Android Automotive OS 앱. 구현 언어는 Java, 차량 연동은 공식 Vehicle SDK.

## Users

어린 자녀와 차로 이동하는 가족. 주차 후 아이와 보호자가 방금 끝낸 이동을 함께 돌아본다.

## Product Purpose

읽을 수 있는 차량 상태에 자동차 캐릭터가 반응하고, 주차 후 차량이 제공하는 트립 기록으로 짧은 일기를 만든다.

## Capabilities and Constraints

주차 상태와 주행 제한이 확인될 때만 일기와 꾸미기를 제공한다. 트립 API를 쓸 수 없으면 속도 읽기 권한을 받아 주차에서 출발해 다시 주차할 때까지의 거리와 움직인 시간을 계산한다. 확인할 수 없는 값은 생략한다.
