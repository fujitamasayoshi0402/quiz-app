package com.quizapp.support.fake

import com.quizapp.quiz.domain.LimitedResource
import com.quizapp.quiz.domain.TenantCapacity
import com.quizapp.quiz.domain.Usage

/** 上限のないテナント（運用者が作ったテナントと同じ）。上限そのものは `TenantCapacityApiTest` が DB で確かめる */
object UnlimitedTenantCapacity : TenantCapacity {
    override fun usage(resource: LimitedResource): Usage? = null
}
