#include "linked_function_classes.hpp"

#include <atomic>
#include <map>
#include <mutex>
#include <set>
#include <stdexcept>
#include <utility>

namespace qod {
namespace {
struct Linked {
	std::mutex mutex;
	std::atomic<bool> frozen {false};
	std::map<std::pair<std::string, std::string>, FunctionClass> classes;
	std::set<std::string> names;
};
Linked &State() {
	static Linked state;
	return state;
}
} // namespace

void RegisterLinkedFunctionClass(const std::string &lower_name, const std::string &type, FunctionClass cls) {
	auto &state = State();
	std::lock_guard<std::mutex> lock(state.mutex);
	if (state.frozen) {
		throw std::logic_error("linked function classes are frozen");
	}
	if (type != "scalar" && type != "aggregate" && type != "table") {
		throw std::logic_error("unsupported linked function type " + type);
	}
	if (cls == FunctionClass::Superuser || cls == FunctionClass::File) {
		throw std::logic_error("a linked function cannot be classed superuser or file");
	}
	if (IsCompiledClassifiedFunction(lower_name)) {
		throw std::logic_error("function " + lower_name + " is already classified");
	}
	state.classes[{lower_name, type}] = cls;
	state.names.insert(lower_name);
}

void FreezeLinkedFunctionClasses() {
	State().frozen = true;
}

std::optional<FunctionClass> LinkedFunctionClassOf(const std::string &lower_name, const std::string &type) {
	auto &state = State();
	std::lock_guard<std::mutex> lock(state.mutex);
	auto found = state.classes.find({lower_name, type});
	if (found == state.classes.end()) {
		return std::nullopt;
	}
	return found->second;
}

bool IsLinkedFunction(const std::string &lower_name) {
	auto &state = State();
	std::lock_guard<std::mutex> lock(state.mutex);
	return state.names.count(lower_name) != 0;
}

bool IsLinkedAdmittedTableFunction(const std::string &lower_name) {
	auto cls = LinkedFunctionClassOf(lower_name, "table");
	return cls && (*cls == FunctionClass::Allowed || *cls == FunctionClass::Listing);
}

} // namespace qod
