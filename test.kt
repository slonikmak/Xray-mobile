import com.android.build.api.variant.VariantBuilder
import com.android.build.api.variant.HasUnitTestBuilder
fun test(v: VariantBuilder) {
    if (v is HasUnitTestBuilder) {
        v.enableUnitTest = true
    }
}
