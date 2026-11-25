// A minimal extra extension: one allowed scalar function, linked through QOD_EXTRA_EXTENSIONS.
#include "duckdb.hpp"
#include "duckdb/main/extension.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "linked_function_classes.hpp"

namespace {
using namespace duckdb;
void Answer(DataChunk &args, ExpressionState &, Vector &result) {
	result.SetVectorType(VectorType::CONSTANT_VECTOR);
	ConstantVector::GetData<int32_t>(result)[0] = 42;
}
class ExtraFixture : public Extension {
public:
	void Load(ExtensionLoader &loader) override {
		loader.RegisterFunction(ScalarFunction("qod_extra_fixture_answer", {}, LogicalType::INTEGER, Answer));
		qod::RegisterLinkedFunctionClass("qod_extra_fixture_answer", "scalar", qod::FunctionClass::Allowed);
	}
	std::string Name() override {
		return "qod_extra_fixture";
	}
};
} // namespace

extern "C" int32_t duckdb_extension_qod_extra_fixture_describe(duckdb_extension_descriptor *descriptor) {
	return duckdb::StaticExtensionDescriber<ExtraFixture>::Describe(descriptor);
}
