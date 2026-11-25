#include "function_classes.hpp"
#include "linked_function_classes.hpp"

#include <iostream>
#include <stdexcept>
#include <string>

namespace {
int failures = 0;
void Check(const std::string &what, bool ok) {
	if (!ok) {
		std::cerr << "FAIL " << what << std::endl;
		++failures;
	}
}
template <class F>
bool Throws(F f) {
	try {
		f();
	} catch (std::logic_error &) {
		return true;
	}
	return false;
}
} // namespace

int main() {
	using qod::FunctionClass;
	Check("unknown before registration", !qod::FunctionClassOf("qod_test_linked_scan", "table"));
	qod::RegisterLinkedFunctionClass("qod_test_linked_scan", "table", FunctionClass::Allowed);
	qod::RegisterLinkedFunctionClass("qod_test_linked_value", "scalar", FunctionClass::Allowed);
	Check("table class", qod::FunctionClassOf("qod_test_linked_scan", "table") == FunctionClass::Allowed);
	Check("scalar class", qod::FunctionClassOf("qod_test_linked_value", "scalar") == FunctionClass::Allowed);
	Check("classified", qod::IsClassifiedFunction("qod_test_linked_value"));
	Check("admitted table function", qod::IsLinkedAdmittedTableFunction("qod_test_linked_scan"));
	Check("scalar is not an admitted table function", !qod::IsLinkedAdmittedTableFunction("qod_test_linked_value"));
	Check("cannot reclassify a built-in", Throws([] {
		      qod::RegisterLinkedFunctionClass("read_csv", "table", FunctionClass::Allowed);
	      }));
	Check("cannot grant superuser", Throws([] {
		      qod::RegisterLinkedFunctionClass("qod_test_linked_admin", "scalar", FunctionClass::Superuser);
	      }));
	qod::FreezeLinkedFunctionClasses();
	Check("frozen", Throws([] {
		      qod::RegisterLinkedFunctionClass("qod_test_linked_late", "scalar", FunctionClass::Allowed);
	      }));
	std::cout << (failures == 0 ? "ok" : "failed") << std::endl;
	return failures == 0 ? 0 : 1;
}
