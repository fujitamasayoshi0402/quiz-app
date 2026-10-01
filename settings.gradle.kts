rootProject.name = "quiz-app"

include(":services:quiz-service")
// サービスの間で共有するイベントの型（ADR-0022）
include(":libs:quiz-events")
