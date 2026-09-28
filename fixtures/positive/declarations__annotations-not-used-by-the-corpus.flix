@Deprecated
def old(): Int32 = 1

@DontInline
def keep(): Int32 = 2

@Skip
@Test
def skipped(): Bool = true
