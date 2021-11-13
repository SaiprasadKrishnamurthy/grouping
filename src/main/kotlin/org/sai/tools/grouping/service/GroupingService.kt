package org.sai.tools.grouping.service

import org.sai.tools.grouping.entity.Member
import org.sai.tools.grouping.model.EntryComparison
import org.sai.tools.grouping.model.GroupInput
import org.sai.tools.grouping.model.GroupOutput
import org.sai.tools.grouping.repository.MemberRepository
import org.springframework.stereotype.Service
import java.util.*

@Service
class GroupingService(private val memberRepository: MemberRepository) {

    fun groups(groupInput: GroupInput): GroupOutput {

        val delimiter = groupInput.delimiter
        val inputs = groupInput.entries
        val mapped = inputs.map {
            "|" + it.split(delimiter).map { s -> s.trim() }.filter { s -> s.isNotBlank() }.joinToString("|") + "|"
        }
        val jobId = UUID.randomUUID().toString()
        val distinctValues = mutableSetOf<String>()

        mapped.forEach { i ->
            val values = i.split("|").map { it.trim() }.filter { it.isNotBlank() }
            values.forEach { v ->
                memberRepository.save(Member(value = v, fullinput = i, jobid = jobId))
                distinctValues.add(v)
            }
        }

        // Get all cardinalities (max first)
        val allCardinalities = memberRepository.getAllCardinalities(jobId)

        val groups = mutableListOf<LinkedHashSet<String>>()

        allCardinalities.forEach { cardinality ->
            val group = linkedSetOf<String>()
            val allValuesFlattened = groups.map { it }.flatten()
            if (!allValuesFlattened.contains(cardinality.getValue())) {
                val value = cardinality.getValue()
                gatherCardinalities(jobId, allValuesFlattened, group, listOf(value))
            }
            if (group.size > 1) {
                groups.add(group)
            }
        }
        val groupValues = groups.flatten()
        val ungroupedValues = distinctValues.filter { !groupValues.contains(it) }
        val entriesAndGroups = mutableMapOf<Set<String>, MutableSet<MutableSet<String>>>()
        val groupsAndNames = mutableMapOf<Set<String>, String>()
        val allEntries = mutableSetOf<Set<String>>()
        groupInput.entries.forEach { entry ->
            val entries = entry.split(delimiter).filter { it.trim().isNotBlank() }.map { it.trim() }.toSet()
            allEntries.add(entries)
            groups.forEachIndexed { i, g ->
                val gMinusE = g.minus(entries)
                groupsAndNames[g] = "Group_$i"
                // The group fully fits here.
                if (gMinusE.isEmpty()) {
                    if (entriesAndGroups[entries] == null) {
                        entriesAndGroups[entries] = mutableSetOf(g)
                    } else {
                        entriesAndGroups[entries]?.add(g)
                    }
                }
            }
        }


        val entryComparisons = mutableListOf<EntryComparison>()
        val unmatched = allEntries.filter { e -> entriesAndGroups.keys.none { it == e } }
            .map { EntryComparison(it.joinToString(", "), it.joinToString(", ")) }
            .filter { it.original.trim().isNotBlank() }

        entryComparisons.addAll(unmatched)
        entriesAndGroups.forEach { (entry, g) ->
            val remaining = entry.filter { e -> g.none { x -> x.contains(e) } }
            val matchedGroups = g.map { x -> groupsAndNames[x] }
            val compressed = remaining + matchedGroups
            val entryComparison = EntryComparison(entry.joinToString(", "), compressed.filterNotNull()
                .filter { it.trim().isNotBlank() }
                .joinToString(", "))
            entryComparisons.add(entryComparison)
        }


        return GroupOutput(
            distinctValues.size,
            groups.size + ungroupedValues.size - 1,
            ungroupedValues,
            groups.map { it.sorted().toSortedSet() },
            comparisons = entryComparisons
        )
    }

    private fun gatherCardinalities(
        jobId: String,
        alreadyGrouped: List<String>,
        group: LinkedHashSet<String>,
        runningValues: List<String>
    ) {
        if (runningValues.size == 1) {
            group.add(runningValues[0])
        }
        val fullinput =
            if (runningValues.size > 1) "|" + runningValues.joinToString("") { "$it|" } else runningValues.joinToString(
                ""
            ) { "|$it|" }
        val bestCardinalities = memberRepository.getBestCardinalities(fullinput, jobId)
            .map { it.getValue() }
            .filter { !alreadyGrouped.contains(it) }

        if (bestCardinalities.isNotEmpty() && bestCardinalities.size > runningValues.size) {
            val nextBestValue = bestCardinalities[runningValues.size]
            group.add(nextBestValue)
            gatherCardinalities(jobId, alreadyGrouped, group, runningValues + listOf(nextBestValue))
        }
    }
}