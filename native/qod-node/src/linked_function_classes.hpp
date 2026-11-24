#pragma once

#include "function_classes.hpp"

#include <optional>
#include <string>

namespace qod {

//! Classes for functions of extensions linked into this binary (QOD_EXTRA_EXTENSIONS): only code
//! compiled into qod-node can call this, and only before FreezeLinkedFunctionClasses, which qod-node
//! calls before it serves. Throws std::logic_error after the freeze, for a name the compiled table
//! classifies, for a type other than scalar/aggregate/table, or for a Superuser or File class.
void RegisterLinkedFunctionClass(const std::string &lower_name, const std::string &type, FunctionClass cls);
void FreezeLinkedFunctionClasses();
std::optional<FunctionClass> LinkedFunctionClassOf(const std::string &lower_name, const std::string &type);
bool IsLinkedFunction(const std::string &lower_name);
bool IsLinkedAdmittedTableFunction(const std::string &lower_name);

} // namespace qod
