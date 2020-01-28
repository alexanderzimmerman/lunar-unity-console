package spacemadness.com.lunarconsole.console

import spacemadness.com.lunarconsole.utils.LimitSizeList
import spacemadness.com.lunarconsole.utils.hasPrefix
import spacemadness.com.lunarconsole.utils.length

/**
 * Represents a list of console log entries.
 * Supports collapsing similar items and filtering by text and log type.
 * @param capacity the maximum amount of entries the list can store
 * @param trimSize the number of entries trimmed when the list overflows
 */
class LogEntryList(capacity: Int, trimSize: Int) {
    /** Stores all entries  */
    private val entryList = LimitSizeList<LogEntry>(capacity, trimSize)

    /** Holds a reference to the current entry list */
    private var currentEntryList = entryList

    /** Stores only filtered entries (or null if filtering is not enabled)  */
    private var filteredEntryList: LimitSizeList<LogEntry>? = null

    /** Current filtering text  */
    var filterText: String? = null
        private set

    private val filteredTempList = mutableListOf<LogEntry>()
    private val collapsedTempList = mutableListOf<CollapsedLogEntry>()

    /** Collapsed entries lookup */
    private val entryLookup: LogEntryLookup =
        LogEntryHashTableLookup()

    /** Holds disabled log entryList types bit mask  */
    private var logDisabledTypesMask: Int = 0

    /** Total count of 'plain' log messages  */
    var logCount: Int = 0
        private set

    /** Total count of 'warning' log messages  */
    var warningCount: Int = 0
        private set

    /** Total count of 'error' log messages  */
    var errorCount: Int = 0
        private set

    /** Returns true if similar entries are collapsed */
    // TODO: rename to 'isCollapsing'
    var isCollapsed: Boolean = false
        private set

    //region Text representation

    val entries: Iterable<LogEntry> get() = entryList

    /** Concatenates every message from all the entryList into a single string */
    @Deprecated("Plain text should not be used")
    fun asString(): String {
        val text = StringBuilder()

        var index = 0
        val count = currentEntryList.count()
        for (entry in currentEntryList) {
            text.append(entry.message)
            if (entry.type == LogEntryType.EXCEPTION && entry.hasStackTrace) {
                text.append('\n')
                text.append(entry.stackTrace)
            }

            if (++index < count) {
                text.append('\n')
            }
        }

        return text.toString()
    }

    //endregion

    val isFiltering: Boolean
        get() = filteredEntryList != null

    val isOverfloating: Boolean
        get() = currentEntryList.isOverflowing

    val isTrimmed: Boolean
        get() = currentEntryList.isTrimmed

    //region Entries

    fun addAll(entries: List<LogEntry>, diff: Diff? = null) {
        // remember old state
        val oldHeadIndex = currentEntryList.overflowCount()
        val oldSize = currentEntryList.totalCount()

        // count types
        updateTypeCounters(entries)

        // we need to retain all entries before filtering (otherwise they might be lost)
        entryList.addAll(entries)

        if (isFiltering) {
            // filter entries into a temporary list to avoid unnecessary allocations
            filterEntries(entries, filteredTempList)

            // check if any items passed the filter
            if (filteredTempList.size > 0) {
                val filteredList = filteredEntryList!!

                // we need to "collapse" similar items and store them in a separate list
                if (isCollapsed) {
                    var entryIndex = 0
                    while (entryIndex < filteredTempList.size) {
                        val collapsedEntry = entryLookup.addEntry(filteredTempList[entryIndex++])
                        // check if the entry was not collapsed before or if it was trimmed
                        if (collapsedEntry.count == 1 || collapsedEntry.index < filteredList.trimmedCount()) {
                            collapsedEntry.index = filteredList.totalCount()
                            filteredList.addObject(collapsedEntry)
                        }
                        // remember each updated collapsed item for the diff result
                        if (diff != null) {
                            collapsedTempList.add(collapsedEntry)
                        }
                    }
                } else {
                    filteredList.addAll(filteredTempList)
                }
                filteredTempList.clear()
            }
        }

        // update diff
        diff?.let {
            diff.trimCount = currentEntryList.overflowCount() - oldHeadIndex
            diff.addCount = currentEntryList.totalCount() - oldSize
            diff.dirtyCollapsedEntries.clear()
            var i = 0
            while (i < collapsedTempList.size) {
                val collapsedEntry = collapsedTempList[i++]
                if (collapsedEntry.index >= currentEntryList.overflowCount()) {
                    diff.dirtyCollapsedEntries.add(collapsedEntry)
                }
            }
            collapsedTempList.clear()
        }
    }

    operator fun get(index: Int) = currentEntryList[index]

    /** Returns entry at index  */
    // TODO: remove this function
    fun getEntry(index: Int) = this[index]

    /** Return collapsed entry at index or null if entry is not collapsed  */
    fun getCollapsedEntry(index: Int) = getEntry(index) as? CollapsedLogEntry

    /** Removes all entryList from the list  */
    fun clear() {
        entryList.clear()
        filteredEntryList?.clear()
        entryLookup.clear()

        logCount = 0
        warningCount = 0
        errorCount = 0
    }

    private fun updateTypeCounters(entries: List<LogEntry>) {
        var i = 0
        while (i < entries.size) {
            updateTypeCounters(entries[i])
            ++i
        }
    }

    private fun updateTypeCounters(entry: LogEntry) {
        val entryType = entry.type
        if (entryType == LogEntryType.LOG) {
            ++logCount
        } else if (entryType == LogEntryType.WARNING) {
            ++warningCount
        } else if (entryType.isErrorType) {
            ++errorCount
        }
    }

    //region Filtering

    /**
     * Sets text-based filtering
     *
     * @param text a new text to filter or "" to remove the text filter
     * @return true if list was changed
     */
    fun setFilterByText(text: String): Boolean {
        if (filterText != text) { // filter text has changed
            val oldFilterText = filterText
            filterText = text

            if (text.length() > oldFilterText.length() && (oldFilterText.length() == 0 || text.hasPrefix(
                    oldFilterText
                ))
            ) {
                return appendFilter()
            }

            return applyFilter()

        }

        return false
    }

    /**
     * Sets log type based filtering
     *
     * @param logType the target log type
     * @param disabled true if log type should be disabled
     * @return true if list was changed
     */
    fun setFilterByLogType(logType: LogEntryType, disabled: Boolean): Boolean {
        return setFilterByLogTypeMask(getMask(logType), disabled)
    }

    /**
     * Sets log type based filtering
     *
     * @param logTypeMask the target log type mask
     * @param disabled true if log type mask should be disabled
     * @return true if list was changed
     */
    fun setFilterByLogTypeMask(logTypeMask: Int, disabled: Boolean): Boolean {
        val oldDisabledTypesMask = logDisabledTypesMask
        if (disabled) {
            logDisabledTypesMask = logDisabledTypesMask or logTypeMask
        } else {
            logDisabledTypesMask = logDisabledTypesMask and logTypeMask.inv()
        }

        if (oldDisabledTypesMask != logDisabledTypesMask) {
            if (disabled) {
                appendFilter()
            } else {
                applyFilter()
            }
            return true
        }

        return false
    }

    /**
     * Checks if log type is enabled
     *
     * @param type the target log type
     * @return true if log type is enabled
     */
    fun isFilterLogTypeEnabled(type: LogEntryType): Boolean {
        return logDisabledTypesMask and getMask(type) == 0
    }

    /**
     * Appends new filter to already filtered items. As a result the number of filtered items may
     * remain unchanged or decrease.
     * @return true if list was changed
     */
    private fun appendFilter(): Boolean {
        if (isFiltering) {
            val filteredList = filteredEntryList
                ?: throw IllegalStateException("Filtered entry list cannot be null")
            useFilteredFromEntries(filteredList)
            return true
        }

        return applyFilter()
    }

    /**
     * Replaces or removes the current filter
     * @return true if list was changed
     */
    private fun applyFilter(): Boolean {
        val needsFiltering =
            isCollapsed || filterText.length() > 0 || hasLogTypeFilters() // needs filtering?
        if (needsFiltering) {
            entryLookup.clear()

            useFilteredFromEntries(entryList)
            return true
        }

        return removeFilter()
    }

    /**
     * Returns current filter
     * @return true if filter was removed
     */
    private fun removeFilter(): Boolean {
        if (isFiltering) {
            currentEntryList = entryList
            filteredEntryList = null

            return true
        }

        return false
    }

    /**
     * Filter entryList and set them as current entryList
     *
     * @param entries entryList to filter
     */
    private fun useFilteredFromEntries(entries: LimitSizeList<LogEntry>) {
        val filteredEntries = filterEntries(entries)

        // use filtered items
        this.currentEntryList = filteredEntries

        // store filtered items
        this.filteredEntryList = filteredEntries
    }

    /**
     * Creates new list based by applying current filter to entryList
     * @param entries entryList to filter
     * @return new list
     */
    private fun filterEntries(entries: LimitSizeList<LogEntry>): LimitSizeList<LogEntry> {
        // TODO: add filtering to the LimitSizeList
        val list =
            LimitSizeList<LogEntry>(entries.capacity(), entries.trimSize) // same as original list

        if (isCollapsed) {
            for (entry in entries) {
                if (filterEntry(entry)) {
                    var collapsedEntry = entry as? CollapsedLogEntry
                    if (collapsedEntry != null) {
                        collapsedEntry.index = list.totalCount() // update item's position
                        list.addObject(collapsedEntry)
                    } else {
                        collapsedEntry = entryLookup.addEntry(entry)
                        if (collapsedEntry.count == 1) { // first encounter
                            collapsedEntry.index = list.totalCount()
                            list.addObject(collapsedEntry)
                        }
                    }
                }
            }
        } else {
            for (entry in entries) {
                if (filterEntry(entry)) {
                    list.addObject(entry)
                }
            }
        }

        return list
    }

    private fun filterEntries(entries: List<LogEntry>, result: MutableList<LogEntry>) {
        var i = 0
        while (i < entries.size) {
            val entry = entries[i]
            if (filterEntry(entry)) {
                result.add(entry)
            }
            ++i
        }
    }

    /**
     * Checks if entry passes the current filter
     * @param entry entry to check
     * @return true if entry passes the filter
     */
    private fun filterEntry(entry: LogEntry): Boolean {
        // filter by log type
        if (logDisabledTypesMask and getMask(entry.type) != 0) {
            return false
        }
        // filter by message
        val filterText = filterText
        return filterText.isNullOrEmpty() || entry.message.contains(filterText, ignoreCase = true)
    }

    /**
     * Check if list is filtering by log type mask
     * @return true if list is filtering by log type mask
     */
    private fun hasLogTypeFilters(): Boolean {
        return logDisabledTypesMask != 0
    }

    //endregion

    //region Getters/Setters

    /**
     * Sets the boolean flag controlling similar entries collapse
     * @param collapsed `true` if similar entries should collapse
     */
    fun setCollapsed(collapsed: Boolean) {
        isCollapsed = collapsed
        applyFilter()
    }

    // FIXME: replace with property
    fun capacity(): Int {
        return currentEntryList.capacity()
    }

    // FIXME: replace with property
    fun count(): Int {
        return currentEntryList.count()
    }

    // FIXME: replace with property
    fun trimCount(): Int {
        return currentEntryList.trimSize
    }

    // FIXME: replace with property
    fun totalCount(): Int {
        return currentEntryList.totalCount()
    }

    // FIXME: replace with property
    fun overflowAmount(): Int {
        return currentEntryList.overflowCount()
    }

    // FIXME: replace with property
    fun trimmedCount(): Int {
        return currentEntryList.trimmedCount()
    }

    class Diff {
        var trimCount = 0
        var addCount = 0
        val dirtyCollapsedEntries = mutableListOf<CollapsedLogEntry>()
    }
}