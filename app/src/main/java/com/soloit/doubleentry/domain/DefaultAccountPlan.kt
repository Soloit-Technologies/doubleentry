package com.soloit.doubleentry.domain

/**
 * A default chart of accounts inspired by the Swedish BAS plan, adapted for personal finance:
 * 12xx–13xx fixed assets, 16xx–17xx receivables, 19xx cash and investments, 20xx equity,
 * 21xx–28xx liabilities, 3xxx income, 4xxx home, 50xx–52xx transport and travel, 6xxx food and
 * household, 7xxx personal and leisure, and 8xxx family, finance, health, insurance,
 * depreciation and tax. The groups line up with [CashflowSettings.DEFAULT_CASH_GROUPS] and with
 * the report groups seeded alongside the accounts. Ids are deterministic so that seeding the plan
 * on several devices converges on the same rows under cloud sync.
 */
object DefaultAccountPlan {

    data class Entry(val number: Int, val name: String, val type: AccountType) {
        val id: String get() = DeterministicIds.defaultPlanAccountId(number)
    }

    val accounts: List<Entry> = listOf(
        // 121x Belongings (depreciated through the asset register into the matching 871x)
        Entry(1211, "Furniture & Furnishings", AccountType.Asset),
        Entry(1212, "Sports & Leisure Equipment", AccountType.Asset),
        Entry(1213, "Vehicles", AccountType.Asset),
        Entry(1214, "Electronics & Appliances", AccountType.Asset),
        Entry(1215, "Boats & Recreational Vehicles", AccountType.Asset),
        Entry(1216, "Tools & Garden Equipment", AccountType.Asset),
        Entry(1221, "Jewelry & Watches", AccountType.Asset),
        Entry(1222, "Art & Collectibles", AccountType.Asset),
        Entry(1223, "Precious Metals", AccountType.Asset),

        // 125x Real estate (buildings and improvements depreciate into 8710)
        Entry(1251, "Home – Building", AccountType.Asset),
        Entry(1252, "Home – Land", AccountType.Asset),
        Entry(1253, "Condominium / Co-op Apartment", AccountType.Asset),
        Entry(1254, "Home Improvements", AccountType.Asset),
        Entry(1255, "Vacation Home", AccountType.Asset),
        Entry(1256, "Rental Property", AccountType.Asset),

        // 13xx Long-term holdings
        Entry(1350, "Shares in Private Companies", AccountType.Asset),
        Entry(1380, "Long-term Loans Receivable", AccountType.Asset),
        Entry(1399, "Accumulated Depreciation", AccountType.Asset),

        // 16xx–17xx Receivables & prepaid
        Entry(1610, "Tax Refund Receivable", AccountType.Asset),
        Entry(1620, "Security Deposits", AccountType.Asset),
        Entry(1630, "Insurance Claims Receivable", AccountType.Asset),
        Entry(1670, "Receivables (money lent out)", AccountType.Asset),
        Entry(1680, "Reimbursable Expenses (paid for others)", AccountType.Asset),
        Entry(1690, "Gift Cards & Store Credit", AccountType.Asset),
        Entry(1700, "Prepaid Expenses", AccountType.Asset),

        // 191x Cash
        Entry(1910, "Cash on Hand", AccountType.Asset),
        Entry(1911, "Foreign Currency Cash", AccountType.Asset),

        // 193x–194x Bank accounts
        Entry(1930, "Checking Account", AccountType.Asset),
        Entry(1931, "Joint Checking Account", AccountType.Asset),
        Entry(1935, "Digital Wallet", AccountType.Asset),
        Entry(1940, "Savings Account", AccountType.Asset),
        Entry(1941, "Emergency Fund", AccountType.Asset),
        Entry(1942, "Term Deposits", AccountType.Asset),

        // 195x–197x Investments
        Entry(1950, "Brokerage Account", AccountType.Asset),
        Entry(1951, "Tax-Advantaged Investment Account", AccountType.Asset),
        Entry(1952, "Children's Investment Account", AccountType.Asset),
        Entry(1953, "Bonds", AccountType.Asset),
        Entry(1960, "Retirement Savings", AccountType.Asset),
        Entry(1961, "Employer Pension Plan", AccountType.Asset),
        Entry(1962, "Private Pension Plan", AccountType.Asset),
        Entry(1970, "Cryptocurrency", AccountType.Asset),

        // 20xx Equity
        Entry(2010, "Opening Balance / Net Worth", AccountType.Equity),

        // 21xx Tax liabilities
        Entry(2110, "Tax Payable", AccountType.Liability),

        // 24xx Short-term liabilities
        Entry(2420, "Accrued Expenses (not yet billed)", AccountType.Liability),
        Entry(2430, "Buy Now, Pay Later", AccountType.Liability),
        Entry(2440, "Unpaid Bills", AccountType.Liability),
        Entry(2450, "Money Owed to Others", AccountType.Liability),
        Entry(2480, "Line of Credit / Overdraft", AccountType.Liability),

        // 25xx Loans
        Entry(2510, "Mortgage", AccountType.Liability),
        Entry(2511, "Home Equity Loan", AccountType.Liability),
        Entry(2520, "Student Loan", AccountType.Liability),
        Entry(2530, "Vehicle Loan", AccountType.Liability),
        Entry(2540, "Personal Loan", AccountType.Liability),
        Entry(2590, "Loans from Family & Friends", AccountType.Liability),

        // 28xx Credit cards
        Entry(2810, "Credit Card", AccountType.Liability),
        Entry(2820, "Store Card", AccountType.Liability),

        // 31xx–33xx Work income
        Entry(3110, "Salary", AccountType.Revenue),
        Entry(3120, "Bonus", AccountType.Revenue),
        Entry(3130, "Tips & Commission", AccountType.Revenue),
        Entry(3200, "Employee Benefits", AccountType.Revenue),
        Entry(3300, "Side Income / Freelance", AccountType.Revenue),

        // 34xx–35xx Property & sales
        Entry(3400, "Rental Income", AccountType.Revenue),
        Entry(3410, "Royalties", AccountType.Revenue),
        Entry(3550, "Sold Belongings", AccountType.Revenue),

        // 36xx Benefits & pensions
        Entry(3610, "Child Benefit", AccountType.Revenue),
        Entry(3620, "Parental Leave Benefit", AccountType.Revenue),
        Entry(3630, "Sickness Benefit", AccountType.Revenue),
        Entry(3640, "Unemployment Benefit", AccountType.Revenue),
        Entry(3650, "Pension Income", AccountType.Revenue),
        Entry(3660, "Housing Allowance", AccountType.Revenue),
        Entry(3670, "Study Grants", AccountType.Revenue),
        Entry(3690, "Other Government Benefits", AccountType.Revenue),

        // 37xx Financial income
        Entry(3710, "Interest Income", AccountType.Revenue),
        Entry(3720, "Card Rewards & Cashback", AccountType.Revenue),
        Entry(3730, "Dividends", AccountType.Revenue),
        Entry(3740, "Capital Gains", AccountType.Revenue),
        Entry(3750, "Investment Value Changes", AccountType.Revenue),

        // 38xx–39xx Other income
        Entry(3800, "Gifts Received", AccountType.Revenue),
        Entry(3810, "Inheritance", AccountType.Revenue),
        Entry(3820, "Insurance Payouts", AccountType.Revenue),
        Entry(3830, "Child Support & Alimony Received", AccountType.Revenue),
        Entry(3840, "Prizes & Winnings", AccountType.Revenue),
        Entry(3990, "Other Income", AccountType.Revenue),
        Entry(3997, "Uncategorized Income", AccountType.Revenue),

        // 40xx Home upkeep
        Entry(4020, "Home Maintenance & Repairs", AccountType.Expense),
        Entry(4030, "Home Security", AccountType.Expense),
        Entry(4040, "Pest Control", AccountType.Expense),

        // 41xx Housing costs & utilities
        Entry(4100, "Rent", AccountType.Expense),
        Entry(4111, "HOA / Condo Fees", AccountType.Expense),
        Entry(4112, "Property Tax", AccountType.Expense),
        Entry(4113, "Ground Rent / Leasehold Fees", AccountType.Expense),
        Entry(4121, "Electricity", AccountType.Expense),
        Entry(4122, "Gas", AccountType.Expense),
        Entry(4131, "Water & Sewage", AccountType.Expense),
        Entry(4132, "Heating", AccountType.Expense),
        Entry(4133, "Waste Collection", AccountType.Expense),
        Entry(4140, "Storage Unit", AccountType.Expense),

        // 42xx Furnishing & equipment
        Entry(4211, "Furnishings", AccountType.Expense),
        Entry(4212, "Appliances & Electronics", AccountType.Expense),
        Entry(4213, "Kitchen Equipment", AccountType.Expense),
        Entry(4214, "Bedding & Textiles", AccountType.Expense),
        Entry(4215, "Garden & Plants", AccountType.Expense),
        Entry(4216, "Tools & Hardware", AccountType.Expense),

        // 43xx Home services
        Entry(4311, "Moving Costs", AccountType.Expense),
        Entry(4312, "Cleaning Services", AccountType.Expense),
        Entry(4313, "Garden Services", AccountType.Expense),
        Entry(4314, "Handyman Services", AccountType.Expense),

        // 50xx Transport
        Entry(5010, "Vehicle Maintenance & Repairs", AccountType.Expense),
        Entry(5011, "Tires", AccountType.Expense),
        Entry(5012, "Car Wash & Cleaning", AccountType.Expense),
        Entry(5013, "Vehicle Inspection", AccountType.Expense),
        Entry(5015, "Bicycle Maintenance & Repairs", AccountType.Expense),
        Entry(5020, "Fuel", AccountType.Expense),
        Entry(5021, "EV Charging", AccountType.Expense),
        Entry(5030, "Parking", AccountType.Expense),
        Entry(5034, "Tolls & Congestion Charges", AccountType.Expense),
        Entry(5040, "Vehicle Tax & Registration", AccountType.Expense),
        Entry(5041, "Driving Lessons & License", AccountType.Expense),
        Entry(5045, "Vehicle Lease", AccountType.Expense),
        Entry(5046, "Car Sharing", AccountType.Expense),
        Entry(5050, "Taxi & Rideshare", AccountType.Expense),
        Entry(5051, "Bike & Scooter Rental", AccountType.Expense),
        Entry(5052, "Car Rental", AccountType.Expense),
        Entry(5061, "Train Tickets", AccountType.Expense),
        Entry(5062, "Public Transport", AccountType.Expense),
        Entry(5063, "Flights", AccountType.Expense),
        Entry(5064, "Ferries", AccountType.Expense),
        Entry(5065, "Long-distance Bus", AccountType.Expense),
        Entry(5070, "Traffic & Parking Fines", AccountType.Expense),

        // 51xx–52xx Travel & post
        Entry(5100, "Lodging", AccountType.Expense),
        Entry(5110, "Package Holidays", AccountType.Expense),
        Entry(5120, "Tours & Excursions", AccountType.Expense),
        Entry(5130, "Passports & Visas", AccountType.Expense),
        Entry(5200, "Postage & Shipping", AccountType.Expense),

        // 61xx Food & drink
        Entry(6101, "Groceries", AccountType.Expense),
        Entry(6102, "Restaurants & Takeaway", AccountType.Expense),
        Entry(6103, "Work Lunches", AccountType.Expense),
        Entry(6104, "Cafés", AccountType.Expense),
        Entry(6105, "Bars & Nightlife", AccountType.Expense),
        Entry(6106, "Alcohol & Tobacco", AccountType.Expense),
        Entry(6107, "Snacks & Convenience Stores", AccountType.Expense),

        // 611x Household supplies
        Entry(6110, "Household Supplies", AccountType.Expense),
        Entry(6111, "Toiletries", AccountType.Expense),

        // 71xx Media & communication
        Entry(7100, "Streaming & TV", AccountType.Expense),
        Entry(7101, "Internet", AccountType.Expense),
        Entry(7102, "Mobile Phone", AccountType.Expense),
        Entry(7103, "Music Streaming", AccountType.Expense),
        Entry(7104, "TV License", AccountType.Expense),
        Entry(7110, "News & Magazines", AccountType.Expense),
        Entry(7115, "Books & Audiobooks", AccountType.Expense),
        Entry(7120, "Apps & Software", AccountType.Expense),
        Entry(7121, "Cloud Storage & Online Services", AccountType.Expense),
        Entry(7130, "Video Games", AccountType.Expense),

        // 72xx Gifts & giving
        Entry(7210, "Cash Gifts Given", AccountType.Expense),
        Entry(7211, "Presents", AccountType.Expense),
        Entry(7212, "Donations & Charity", AccountType.Expense),
        Entry(7213, "Celebrations & Parties", AccountType.Expense),
        Entry(7214, "Religious Contributions", AccountType.Expense),

        // 73xx Clothing, kids & pets
        Entry(7310, "Clothing & Shoes", AccountType.Expense),
        Entry(7311, "Laundry & Clothing Repairs", AccountType.Expense),
        Entry(7312, "Accessories & Jewelry", AccountType.Expense),
        Entry(7320, "Baby & Kids Equipment", AccountType.Expense),
        Entry(7321, "Toys", AccountType.Expense),
        Entry(7322, "Diapers & Baby Care", AccountType.Expense),
        Entry(7323, "Kids' Clothing", AccountType.Expense),
        Entry(7324, "Pocket Money", AccountType.Expense),
        Entry(7330, "Pet Food & Supplies", AccountType.Expense),
        Entry(7331, "Veterinary Care", AccountType.Expense),
        Entry(7332, "Pet Services", AccountType.Expense),

        // 74xx Sports & fitness
        Entry(7411, "Membership Fees", AccountType.Expense),
        Entry(7412, "Gym & Classes", AccountType.Expense),
        Entry(7420, "Sports Activities", AccountType.Expense),
        Entry(7430, "Sports Equipment Rental", AccountType.Expense),
        Entry(7441, "Sports & Fitness Equipment", AccountType.Expense),
        Entry(7450, "Outdoor & Camping Gear", AccountType.Expense),

        // 75xx–76xx Entertainment & hobbies
        Entry(7510, "Events & Entrance Tickets", AccountType.Expense),
        Entry(7511, "Cinema & Theater", AccountType.Expense),
        Entry(7512, "Concerts & Festivals", AccountType.Expense),
        Entry(7520, "Games & Lotteries", AccountType.Expense),
        Entry(7610, "Hobbies", AccountType.Expense),
        Entry(7611, "Arts & Crafts", AccountType.Expense),
        Entry(7612, "Photography", AccountType.Expense),
        Entry(7613, "Musical Instruments & Lessons", AccountType.Expense),

        // 81xx Family & education
        Entry(8111, "Childcare", AccountType.Expense),
        Entry(8112, "Children's Activities", AccountType.Expense),
        Entry(8113, "Babysitting", AccountType.Expense),
        Entry(8114, "Child Support & Alimony Paid", AccountType.Expense),
        Entry(8120, "Tuition & School Fees", AccountType.Expense),
        Entry(8121, "Courses & Training", AccountType.Expense),
        Entry(8122, "School Supplies", AccountType.Expense),
        Entry(8130, "Elder Care", AccountType.Expense),

        // 82xx Interest & financial fees
        Entry(8211, "Mortgage Interest", AccountType.Expense),
        Entry(8212, "Student Loan Interest", AccountType.Expense),
        Entry(8213, "Vehicle Loan Interest", AccountType.Expense),
        Entry(8214, "Credit Card Interest", AccountType.Expense),
        Entry(8215, "Personal Loan Interest", AccountType.Expense),
        Entry(8219, "Other Interest Expense", AccountType.Expense),
        Entry(8220, "Bank Fees", AccountType.Expense),
        Entry(8221, "Card & Payment Fees", AccountType.Expense),
        Entry(8222, "Currency Exchange Fees", AccountType.Expense),
        Entry(8223, "Investment & Brokerage Fees", AccountType.Expense),
        Entry(8224, "Late Fees & Penalties", AccountType.Expense),

        // 83xx Work & professional services
        Entry(8311, "Union & Professional Fees", AccountType.Expense),
        Entry(8312, "Work Clothing & Equipment", AccountType.Expense),
        Entry(8313, "Home Office", AccountType.Expense),
        Entry(8320, "Accounting & Tax Preparation", AccountType.Expense),
        Entry(8330, "Legal Fees", AccountType.Expense),
        Entry(8340, "Financial Advice", AccountType.Expense),

        // 85xx Health & personal care
        Entry(8510, "Healthcare", AccountType.Expense),
        Entry(8520, "Dental Care", AccountType.Expense),
        Entry(8530, "Hair Care", AccountType.Expense),
        Entry(8531, "Cosmetics & Skincare", AccountType.Expense),
        Entry(8532, "Spa & Massage", AccountType.Expense),
        Entry(8540, "Glasses & Lenses", AccountType.Expense),
        Entry(8550, "Therapy & Mental Health", AccountType.Expense),
        Entry(8551, "Physiotherapy", AccountType.Expense),
        Entry(8560, "Pharmacy", AccountType.Expense),
        Entry(8561, "Prescription Medicine", AccountType.Expense),
        Entry(8563, "Medical Supplies", AccountType.Expense),
        Entry(8564, "Vaccinations", AccountType.Expense),

        // 86xx Insurance
        Entry(8610, "Insurance Deductibles", AccountType.Expense),
        Entry(8611, "Life Insurance", AccountType.Expense),
        Entry(8612, "Accident Insurance", AccountType.Expense),
        Entry(8613, "Disability Insurance", AccountType.Expense),
        Entry(8614, "Health Insurance", AccountType.Expense),
        Entry(8615, "Dental Insurance", AccountType.Expense),
        Entry(8621, "Home Insurance", AccountType.Expense),
        Entry(8622, "Contents / Renters Insurance", AccountType.Expense),
        Entry(8623, "Vehicle Insurance", AccountType.Expense),
        Entry(8624, "Liability Insurance", AccountType.Expense),
        Entry(8625, "Pet Insurance", AccountType.Expense),
        Entry(8626, "Travel Insurance", AccountType.Expense),
        Entry(8627, "Device Insurance", AccountType.Expense),

        // 87xx Depreciation (871x mirrors the belongings in 121x)
        Entry(8710, "Depreciation – Buildings", AccountType.Expense),
        Entry(8711, "Depreciation – Furniture", AccountType.Expense),
        Entry(8712, "Depreciation – Sports & Leisure Equipment", AccountType.Expense),
        Entry(8713, "Depreciation – Vehicles", AccountType.Expense),
        Entry(8714, "Depreciation – Electronics & Appliances", AccountType.Expense),
        Entry(8715, "Depreciation – Boats & Recreational Vehicles", AccountType.Expense),
        Entry(8716, "Depreciation – Tools & Garden Equipment", AccountType.Expense),

        // 89xx Tax & uncategorized
        Entry(8910, "Income Tax", AccountType.Expense),
        Entry(8920, "Capital Gains Tax", AccountType.Expense),
        Entry(8930, "Social Security Contributions", AccountType.Expense),
        Entry(8940, "Wealth Tax", AccountType.Expense),
        Entry(8960, "Tax Penalties & Interest", AccountType.Expense),
        Entry(8997, "Uncategorized Expenses", AccountType.Expense),
        Entry(8998, "Other Expenses", AccountType.Expense),
    )

    /** Balance sheet groups matching the number ranges above. */
    val balanceSheetSettings = BalanceSheetSettings(
        assetGroups = listOf(
            AccountGroup("Belongings", listOf("121", "122")),
            AccountGroup("Real Estate", listOf("125")),
            AccountGroup("Long-term Holdings", listOf("135", "138")),
            AccountGroup("Accumulated Depreciation", listOf("1399")),
            AccountGroup("Receivables & Prepaid", listOf("16", "17")),
            AccountGroup("Cash & Bank", listOf("191", "193", "194")),
            AccountGroup("Investments", listOf("195", "197")),
            AccountGroup("Retirement Savings", listOf("196")),
        ),
        liabilityGroups = listOf(
            AccountGroup("Tax Liabilities", listOf("21")),
            AccountGroup("Short-term Liabilities", listOf("24")),
            AccountGroup("Loans", listOf("25")),
            AccountGroup("Credit Cards", listOf("28")),
        ),
    )

    /** Income statement groups and subtotal prefixes matching the number ranges above. */
    val incomeStatementSettings = IncomeStatementSettings(
        revenueGroups = listOf(
            AccountGroup("Work Income", listOf("31", "32", "33")),
            AccountGroup("Property & Sales", listOf("34", "35")),
            AccountGroup("Benefits & Pensions", listOf("36")),
            AccountGroup("Financial Income", listOf("37")),
            AccountGroup("Other Income", listOf("38", "39")),
        ),
        expenseGroups = listOf(
            AccountGroup("Housing & Utilities", listOf("40", "41")),
            AccountGroup("Furnishing & Home Services", listOf("42", "43")),
            AccountGroup("Transport", listOf("50")),
            AccountGroup("Travel & Post", listOf("51", "52")),
            AccountGroup("Food & Household", listOf("61")),
            AccountGroup("Media & Communication", listOf("71")),
            AccountGroup("Gifts & Giving", listOf("72")),
            AccountGroup("Clothing, Kids & Pets", listOf("73")),
            AccountGroup("Sports & Fitness", listOf("74")),
            AccountGroup("Entertainment & Hobbies", listOf("75", "76")),
            AccountGroup("Family & Education", listOf("81")),
            AccountGroup("Interest & Financial Fees", listOf("82")),
            AccountGroup("Work & Professional Services", listOf("83")),
            AccountGroup("Health & Personal Care", listOf("85")),
            AccountGroup("Insurance", listOf("86")),
            AccountGroup("Depreciation", listOf("87")),
            AccountGroup("Taxes", listOf("891", "892", "893", "894", "896")),
            AccountGroup("Other Expenses", listOf("899")),
        ),
        depreciationPrefixes = listOf("87"),
        taxPrefixes = listOf("891", "892", "893", "894", "896"),
        // Pension savings can't be spent, so income routed there is shown as deferred.
        deferredAssetPrefixes = listOf("196"),
    )
}
