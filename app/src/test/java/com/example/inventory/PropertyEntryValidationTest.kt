package com.example.inventory
import org.junit.Assert.*
import org.junit.Test
class PropertyEntryValidationTest {
 @Test fun requiresProjectAndLocation(){assertEquals("Project / building is required",PropertyEntryValidation.error(PropertyEntryDraft("","Kharadi","","","")))}
 @Test fun acceptsQuickListingWithOptionalNumbers(){assertNull(PropertyEntryValidation.error(PropertyEntryDraft("Gera","Kharadi","","","")))}
 @Test fun rejectsInvalidArea(){assertNotNull(PropertyEntryValidation.error(PropertyEntryDraft("Gera","Kharadi","","0","")))}
}

