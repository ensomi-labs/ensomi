package io.ensomi.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeTextTest {
    private val wrapped = """
        MIT License

        Copyright (c) Microsoft Corporation

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software.

        Source Code Compliance Team
        Microsoft Corporation
        One Microsoft Way

        Use and Redistribution. You may use and redistribute the software, without
        modification, provided the following conditions are met:
        * Redistributions must reproduce the above copyright notice and the following
          terms of use in the Software.
        * Neither the name of Intel nor the names of its suppliers may be used.
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun hardWrappedLinesBecomeOneParagraph() {
        val paragraphs = NoticeText.paragraphs(wrapped)
        assertEquals(5, paragraphs.size)
        assertEquals("MIT License", paragraphs[0])
        assertEquals(
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and " +
                "associated documentation files (the \"Software\"), to deal in the Software without restriction, " +
                "including without limitation the rights to use, copy, modify, merge, publish, distribute, " +
                "sublicense, and/or sell copies of the Software.",
            paragraphs[2],
        )
    }

    @Test
    fun shortLinesAndListItemsKeepTheirBreaks() {
        val paragraphs = NoticeText.paragraphs(wrapped)
        assertEquals("Source Code Compliance Team\nMicrosoft Corporation\nOne Microsoft Way", paragraphs[3])
        assertEquals(
            "Use and Redistribution. You may use and redistribute the software, without modification, provided the " +
                "following conditions are met:\n" +
                "* Redistributions must reproduce the above copyright notice and the following terms of use in the " +
                "Software.\n" +
                "* Neither the name of Intel nor the names of its suppliers may be used.",
            paragraphs[4],
        )
    }
}
