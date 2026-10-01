package com.soloit.doubleentry.domain

/**
 * A default chart of accounts inspired by the Swedish BAS plan, adapted for personal finance:
 * 1xxx assets, 20xx equity, 23xx–29xx liabilities, 3xxx income, 5xxx–7xxx expenses and
 * 8xxx financial items. Ids are deterministic so that seeding the plan on several devices
 * converges on the same rows under cloud sync.
 */
object DefaultAccountPlan {

    data class Entry(val number: Int, val name: String, val type: AccountType) {
        val id: String get() = DeterministicIds.defaultPlanAccountId(number)
    }

    val accounts: List<Entry> = listOf(
        // 1xxx Assets
        Entry(1010, "Cash on Hand", AccountType.Asset),
        Entry(1500, "Receivables (money lent out)", AccountType.Asset),
        Entry(1700, "Prepaid Expenses", AccountType.Asset),
        Entry(1800, "Home / Real Estate", AccountType.Asset),
        Entry(1820, "Vehicle", AccountType.Asset),
        Entry(1910, "Checking Account", AccountType.Asset),
        Entry(1920, "Savings Account", AccountType.Asset),
        Entry(1930, "Investment Account (ISK)", AccountType.Asset),
        Entry(1940, "Pension Savings", AccountType.Asset),
        Entry(1950, "Cryptocurrency", AccountType.Asset),

        // 20xx Equity
        Entry(2010, "Opening Balance / Net Worth", AccountType.Equity),

        // 23xx Long-term liabilities
        Entry(2350, "Mortgage", AccountType.Liability),
        Entry(2380, "Student Loan (CSN)", AccountType.Liability),
        Entry(2390, "Vehicle Loan", AccountType.Liability),

        // 24xx–29xx Short-term liabilities
        Entry(2410, "Credit Card", AccountType.Liability),
        Entry(2420, "Personal Loan", AccountType.Liability),
        Entry(2440, "Accounts Payable (unpaid bills)", AccountType.Liability),

        // 3xxx Income
        Entry(3010, "Salary", AccountType.Revenue),
        Entry(3020, "Bonus", AccountType.Revenue),
        Entry(3110, "Side Income / Freelance", AccountType.Revenue),
        Entry(3310, "Interest Income", AccountType.Revenue),
        Entry(3320, "Dividends", AccountType.Revenue),
        Entry(3330, "Capital Gains", AccountType.Revenue),
        Entry(3910, "Gifts Received", AccountType.Revenue),
        Entry(3920, "Tax Refund", AccountType.Revenue),

        // 5xxx Housing & utilities
        Entry(5010, "Rent", AccountType.Expense),
        Entry(5020, "Mortgage Interest", AccountType.Expense),
        Entry(5030, "Electricity", AccountType.Expense),
        Entry(5040, "Water", AccountType.Expense),
        Entry(5050, "Heating", AccountType.Expense),
        Entry(5060, "Internet & TV", AccountType.Expense),
        Entry(5070, "Home Insurance", AccountType.Expense),
        Entry(5080, "Home Maintenance", AccountType.Expense),

        // 56xx Transportation
        Entry(5610, "Fuel", AccountType.Expense),
        Entry(5620, "Vehicle Insurance", AccountType.Expense),
        Entry(5630, "Vehicle Maintenance", AccountType.Expense),
        Entry(5640, "Public Transport", AccountType.Expense),
        Entry(5650, "Parking & Congestion Tax", AccountType.Expense),

        // 6xxx Personal & living
        Entry(6010, "Groceries", AccountType.Expense),
        Entry(6020, "Restaurants & Dining Out", AccountType.Expense),
        Entry(6110, "Clothing", AccountType.Expense),
        Entry(6210, "Mobile Phone", AccountType.Expense),
        Entry(6310, "Personal Insurance", AccountType.Expense),
        Entry(6410, "Healthcare", AccountType.Expense),
        Entry(6420, "Pharmacy", AccountType.Expense),
        Entry(6510, "Personal Care", AccountType.Expense),

        // 68xx Leisure & entertainment
        Entry(6810, "Entertainment & Streaming", AccountType.Expense),
        Entry(6820, "Hobbies", AccountType.Expense),
        Entry(6830, "Travel & Vacation", AccountType.Expense),
        Entry(6840, "Sports & Fitness", AccountType.Expense),
        Entry(6850, "Books & Education", AccountType.Expense),
        Entry(6860, "Subscriptions", AccountType.Expense),

        // 7xxx Family & giving
        Entry(7010, "Childcare", AccountType.Expense),
        Entry(7020, "Children's Activities", AccountType.Expense),
        Entry(7030, "Pets", AccountType.Expense),
        Entry(7040, "Gifts Given", AccountType.Expense),
        Entry(7050, "Donations", AccountType.Expense),

        // 8xxx Financial items
        Entry(8410, "Interest Expense", AccountType.Expense),
        Entry(8910, "Income Tax", AccountType.Expense),
        Entry(8920, "Capital Gains Tax", AccountType.Expense),
    )
}
