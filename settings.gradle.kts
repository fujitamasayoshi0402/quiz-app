rootProject.name = "quiz-app"

include(":services:quiz-service")
// クイズのイベントを受けて、テナントの Slack に知らせる Lambda（ADR-0022）
include(":services:notification-service")
// サービスの間で共有するイベントの型（ADR-0022）
include(":libs:quiz-events")
