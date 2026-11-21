package com.hearthledger.category

import com.hearthledger.common.Necessity
import com.hearthledger.common.badRequest
import com.hearthledger.common.notFound
import com.hearthledger.family.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

data class CategoryRequest(
    @field:NotBlank val name: String,
    val type: CategoryType,
    val parentId: Long? = null,
    val icon: String? = null,
    val color: String? = null,
    val defaultNecessity: Necessity? = null,
    val archived: Boolean = false,
)

@RestController
@RequestMapping("/api/v1/categories")
class CategoryController(private val repo: CategoryRepository, private val current: CurrentUser) {

    @GetMapping
    fun list() = repo.findByFamilyId(current.familyId()).sortedWith(compareBy({ it.type }, { it.name }))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody r: CategoryRequest): Category {
        val familyId = current.requireAdmin().familyId
        validateParent(r, familyId)
        return repo.save(
            Category(familyId = familyId, parentId = r.parentId, type = r.type, name = r.name, icon = r.icon, color = r.color, defaultNecessity = r.defaultNecessity),
        )
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody r: CategoryRequest): Category {
        val familyId = current.requireAdmin().familyId
        val existing = repo.findByIdAndFamilyId(id, familyId) ?: throw notFound("Category")
        validateParent(r, familyId)
        if (r.parentId == id) throw badRequest("INVALID_PARENT", "A category cannot be its own parent")
        return repo.save(
            existing.copy(
                name = r.name, parentId = r.parentId, type = r.type, icon = r.icon, color = r.color,
                defaultNecessity = r.defaultNecessity, archived = r.archived,
            ),
        )
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        val familyId = current.requireAdmin().familyId
        repo.delete(repo.findByIdAndFamilyId(id, familyId) ?: throw notFound("Category"))
    }

    private fun validateParent(r: CategoryRequest, familyId: Long) {
        val parentId = r.parentId ?: return
        val parent = repo.findByIdAndFamilyId(parentId, familyId) ?: throw notFound("Parent category")
        if (parent.parentId != null) throw badRequest("INVALID_PARENT", "Only one level of subcategories is supported")
        if (parent.type != r.type) throw badRequest("INVALID_PARENT", "Subcategory type must match its parent")
    }
}
